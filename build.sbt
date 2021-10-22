inThisBuild(
  List(
    organization := "com.kubukoz",
    homepage := Some(url("https://github.com/kubukoz/spotify-next")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "kubukoz",
        "Jakub Kozłowski",
        "kubukoz@gmail.com",
        url("https://kubukoz.com")
      )
    )
  )
)
Global / onChangedBuildSource := ReloadOnSourceChanges

(ThisBuild / scalaVersion) := "3.1.0"

val GraalVM11 = "graalvm-ce-java11@20.3.0"
ThisBuild / githubWorkflowJavaVersions := Seq(GraalVM11)
ThisBuild / githubWorkflowTargetTags := Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches := List(RefPredicate.StartsWith(Ref.Tag("v")), RefPredicate.Equals(Ref.Branch("main")))

ThisBuild / githubWorkflowOSes := Seq("macos-10.15")

ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(List("nativeImage")),
  WorkflowStep.Use(
    UseRef.Public("softprops", "action-gh-release", "v1"),
    params = Map("files" -> "target/native-image/spotify-next")
  )
)
ThisBuild / githubWorkflowGeneratedCI ~= {
  _.map {
    case job if job.id == "publish" =>
      job.copy(
        oses = List("macos-10.15")
        // steps = job.steps.map {
        //   case step: WorkflowStep.Use if step.name.exists(_.startsWith("Download target directories")) =>
        //     step
        //       .copy(
        //         params = step.params + ("name" -> step.params("name").replace("${{ matrix.os }}", "ubuntu-latest"))
        //       )

        //   case step => step
        // }
      )
    case job                        =>
      job
  }
}

ThisBuild / githubWorkflowEnv ++= List(
  "PGP_PASSPHRASE",
  "PGP_SECRET",
  "SONATYPE_PASSWORD",
  "SONATYPE_USERNAME"
).map { envKey =>
  envKey -> s"$${{ secrets.$envKey }}"
}.toMap

ThisBuild / libraryDependencySchemes ++= Seq(
  "io.circe" %% "circe-core" % "early-semver",
  "io.circe" %% "circe-numbers" % "early-semver",
  "io.circe" %% "circe-jawn" % "early-semver",
  "io.circe" %% "circe-parser" % "early-semver"
)

def crossPlugin(x: sbt.librarymanagement.ModuleID) = compilerPlugin(x.cross(CrossVersion.full))

val addCompilerPlugins = libraryDependencies ++= {
  List(
    crossPlugin("org.polyvariant" % "better-tostring" % "0.3.10")
  )
}

val commonSettings = Seq(
  scalacOptions -= "-Xfatal-warnings",
  scalacOptions ++= List(
    "-rewrite",
    "-source",
    "future-migration",
    "-Ximport-suggestion-timeout",
    "2000"
  ),
  libraryDependencies ++= Seq(
    "org.typelevel" %%% "cats-effect" % "3.2.9",
    "org.scalameta" %%% "munit" % "0.7.29" % Test,
    "org.typelevel" %% "munit-cats-effect-3" % "1.0.6" % Test
  ),
  addCompilerPlugins,
  Compile / doc / sources := Nil
)

// val core = project
// .enablePlugins(ScalaJSPlugin)
// .settings(commonSettings)

/*
val front = project
  .enablePlugins(ScalaJSPlugin)
  .enablePlugins(ScalaJSBundlerPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "me.shadaj" %%% "slinky-core" % "0.6.7",
      "me.shadaj" %%% "slinky-web" % "0.6.7",
      "me.shadaj" %%% "slinky-hot" % "0.6.7",
      "me.shadaj" %%% "slinky-styled-components" % "0.1.0+9-38941d55"
    ),
    //
    fastOptJS / webpackConfigFile := Some(baseDirectory.value / "webpack" / "webpack-fastopt.config.js"),
    fullOptJS / webpackConfigFile := Some(baseDirectory.value / "webpack" / "webpack-opt.config.js"),
    //
    webpackResources := baseDirectory.value / "webpack" * "*",
    fastOptJS / webpackDevServerExtraArgs := Seq("--inline", "--hot"),
    fastOptJS / webpackBundlingMode := BundlingMode.LibraryOnly(),
    webpack / version := "4.29.6",
    startWebpackDevServer / version := "3.3.0",
    Compile / npmDependencies ++= Seq(
      "react" -> "16.11.0",
      "react-dom" -> "16.11.0",
      "react-proxy" -> "1.1.8",
      "source-map-support" -> "0.5.19",
      "styled-components" -> "3.4.10"
    ),
    Compile / npmDevDependencies ++= Seq(
      "file-loader" -> "3.0.1",
      "style-loader" -> "0.23.1",
      "css-loader" -> "2.1.1",
      "html-webpack-plugin" -> "3.2.0",
      "copy-webpack-plugin" -> "5.0.2",
      "webpack-merge" -> "4.2.1"
    ),
    addCommandAlias("dev", ";front/fastOptJS::startWebpackDevServer;~front/fastOptJS"),
    addCommandAlias("build", "front/fullOptJS::webpack")
  )
  .dependsOn(core)
 */

val nativeImageSettings: Seq[Setting[_]] = Seq(
  Compile / mainClass := Some("com.kubukoz.next.Main"),
  nativeImageVersion := "21.2.0",
  nativeImageOptions ++= Seq(
    s"-H:ReflectionConfigurationFiles=${(Compile / resourceDirectory).value / "reflect-config.json"}",
    s"-H:ResourceConfigurationFiles=${(Compile / resourceDirectory).value / "resource-config.json"}",
    "-H:+ReportExceptionStackTraces",
    "--no-fallback",
    "--allow-incomplete-classpath"
  ),
  nativeImageAgentMerge := true,
  nativeImageReady := { () => () }
)

val root =
  project
    .in(file("."))
    .settings(commonSettings)
    .settings(
      libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-mtl" % "1.2.1",
        "com.monovore" %% "decline-effect" % "2.1.0",
        "org.http4s" %% "http4s-dsl" % "0.23.6",
        "org.http4s" %% "http4s-blaze-server" % "0.23.6",
        "org.http4s" %% "http4s-blaze-client" % "0.23.6",
        "org.http4s" %% "http4s-circe" % "0.23.6",
        "ch.qos.logback" % "logback-classic" % "1.2.6",
        "io.circe" %% "circe-parser" % "0.14.1",
        "dev.optics" %% "monocle-core" % "3.0.0"
      ),
      buildInfoKeys := Seq[BuildInfoKey](version),
      buildInfoPackage := "com.kubukoz.next",
      nativeImageSettings
    )
    .settings(name := "spotify-next")
    .enablePlugins(BuildInfoPlugin)
    .enablePlugins(JavaAppPackaging)
    .enablePlugins(NativeImagePlugin)
// .dependsOn(core)
// .aggregate(core /* , front */ )
