package com.kubukoz.next.api

import org.http4s.Uri

object sonos {

  val baseUri: Uri = {
    import org.http4s.implicits.*
    uri"http://localhost:5005"
  }

}
