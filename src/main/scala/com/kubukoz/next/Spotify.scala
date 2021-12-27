package com.kubukoz.next

import cats.FlatMap
import cats.data.OptionT
import cats.effect.Concurrent
import cats.effect.Ref
import cats.implicits.*
import com.kubukoz.next.api.sonos
import com.kubukoz.next.api.spotify.Item
import com.kubukoz.next.api.spotify.Player
import com.kubukoz.next.api.spotify.PlayerContext
import com.kubukoz.next.api.spotify.TrackUri
import io.circe.syntax.*
import org.http4s.Method.DELETE
import org.http4s.Method.POST
import org.http4s.Method.PUT
import org.http4s.Request
import org.http4s.Status
import org.http4s.Uri
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import scala.concurrent.duration.*
import scala.util.chaining.*
import com.kubukoz.next.spotify.SpotifyApi
import com.kubukoz.next.spotify.Track
import com.kubukoz.next.spotify.AudioAnalysis
import com.kubukoz.next.sonos.SonosApi
import com.kubukoz.next.sonos.GetZonesOutput
import cats.MonadError
import cats.ApplicativeError

trait Spotify[F[_]] {
  def skipTrack: F[Unit]
  def dropTrack: F[Unit]
  def fastForward(percentage: Int): F[Unit]
  def jumpSection: F[Unit]
}

object Spotify {

  def apply[F[_]](using F: Spotify[F]): Spotify[F] = F

  enum Error extends Throwable {
    case NotPlaying
    case InvalidStatus(status: Status)
    case NoContext
    case NoItem
    case InvalidContext[T](ctx: T)
    case InvalidItem[T](item: T)
  }

  import Error.*

  def instance[F[_]: Playback: Client: UserOutput: Concurrent: SpotifyApi]: Spotify[F] =
    new Spotify[F] {
      val client = implicitly[Client[F]]

      private def requirePlaylist[A](player: Player[Option[PlayerContext], A]): F[Player[PlayerContext.playlist, A]] =
        player
          .unwrapContext
          .liftTo[F](NoContext)
          .flatMap(_.narrowContext[PlayerContext.playlist].liftTo[F])

      private def requireTrack[A](player: Player[A, Option[Item]]): F[Player[A, Item.track]] =
        player
          .unwrapItem
          .liftTo[F](NoItem)
          .flatMap(_.narrowItem[Item.track].liftTo[F])

      val skipTrack: F[Unit] =
        UserOutput[F].print(UserMessage.SwitchingToNext) *>
          Playback[F].nextTrack

      val dropTrack: F[Unit] =
        methods.player[F].flatMap(requirePlaylist(_)).flatMap(requireTrack).flatMap { player =>
          val trackUri = player.item.uri
          val playlistId = player.context.uri.playlist

          UserOutput[F].print(UserMessage.RemovingCurrentTrack(player)) *>
            skipTrack *>
            implicitly[SpotifyApi[F]].removeTrack(playlistId, List(Track(trackUri.toFullUri)))
        }

      def fastForward(percentage: Int): F[Unit] =
        methods
          .player[F]
          .flatMap(requireTrack)
          .fproduct { player =>
            val currentLength = player.progress_ms
            val totalLength = player.item.duration_ms
            ((currentLength * 100 / totalLength) + percentage)
          }
          .flatMap {
            case (_, desiredProgressPercent) if desiredProgressPercent >= 100 =>
              UserOutput[F].print(UserMessage.TooCloseToEnd) *>
                Playback[F].seek(0)

            case (player, desiredProgressPercent) =>
              val desiredProgressMs = desiredProgressPercent * player.item.duration_ms / 100
              UserOutput[F].print(UserMessage.Seeking(desiredProgressPercent)) *>
                Playback[F].seek(desiredProgressMs)
          }

      def jumpSection: F[Unit] = methods
        .player[F]
        .flatMap(requireTrack)
        .flatMap { player =>
          val track = player.item

          val currentLength = player.progress_ms.millis

          (implicitly[SpotifyApi[F]]
            .getAudioAnalysis(track.uri.id): F[AudioAnalysis])
            .flatMap { analysis =>
              analysis
                .sections
                .zipWithIndex
                .find { case (section, _) => section.startSeconds.seconds > currentLength }
                .traverse { case (section, index) =>
                  val percentage = (section.startSeconds.seconds * 100 / track.duration_ms.millis).toInt

                  UserOutput[F].print(
                    UserMessage.Jumping(
                      sectionNumber = index + 1,
                      sectionsTotal = analysis.sections.length,
                      percentTotal = percentage
                    )
                  ) *>
                    Playback[F].seek(section.startSeconds.seconds.toMillis.toInt)
                }
                .pipe(OptionT(_))
                .getOrElseF(UserOutput[F].print(UserMessage.TooCloseToEnd) *> Playback[F].seek(0))
            }
        }
        .void

    }

  trait Playback[F[_]] {
    def nextTrack: F[Unit]
    def seek(ms: Int): F[Unit]
  }

  object Playback {
    def apply[F[_]](using F: Playback[F]): Playback[F] = F

    def spotifyInstance[F[_]: SpotifyApi]: Playback[F] = new Playback[F] {
      val nextTrack: F[Unit] = summon[SpotifyApi[F]].nextTrack()
      def seek(ms: Int): F[Unit] = summon[SpotifyApi[F]].seek(ms)
    }

    def sonosInstance[F[_]: SonosApi](room: String): Playback[F] = new Playback[F] {
      val nextTrack: F[Unit] =
        summon[SonosApi[F]].nextTrack(room)

      def seek(ms: Int): F[Unit] = {
        val seconds = ms.millis.toSeconds.toInt

        summon[SonosApi[F]].seek(room, seconds)
      }

    }

    def suspend[F[_]: FlatMap](choose: F[Playback[F]]): Playback[F] = new Playback[F] {
      def nextTrack: F[Unit] = choose.flatMap(_.nextTrack)
      def seek(ms: Int): F[Unit] = choose.flatMap(_.seek(ms))
    }

  }

  trait DeviceInfo[F[_]] {
    def isRestricted: F[Boolean]
  }

  object DeviceInfo {
    def apply[F[_]](using F: DeviceInfo[F]): DeviceInfo[F] = F

    def instance[F[_]: Concurrent: Client]: DeviceInfo[F] = new DeviceInfo[F] {
      val isRestricted: F[Boolean] = methods.player[F].map(_.device.is_restricted)
    }

  }

  trait SonosInfo[F[_]] {
    def zones: F[Option[GetZonesOutput]]
  }

  object SonosInfo {
    def apply[F[_]](using F: SonosInfo[F]): SonosInfo[F] = F

    def instance[F[_]: UserOutput: SonosApi](using ApplicativeError[F, ?]): SonosInfo[F] =
      new SonosInfo[F] {

        def zones: F[Option[GetZonesOutput]] = UserOutput[F].print(UserMessage.CheckingSonos) *>
          (summon[SonosApi[F]].getZones(): F[GetZonesOutput]).map(_.some) // .attempt.map(_.toOption)

      }

  }

  private object methods {

    def player[F[_]: Concurrent: Client]: F[Player[Option[PlayerContext], Option[Item]]] =
      summon[Client[F]].expectOr(com.kubukoz.next.api.spotify.baseUri / "v1" / "me" / "player") {
        case response if response.status === Status.NoContent => NotPlaying.pure[F].widen
        case response                                         => InvalidStatus(response.status).pure[F].widen
      }

  }

}
