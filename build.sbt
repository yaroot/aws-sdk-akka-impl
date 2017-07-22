organization  := "com.example"
version       := "0.1"
scalaVersion  := "2.12.2"
scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

//resolvers += Resolver.sonatypeRepo("releases")

val V = new {
  val akka        = "2.5.3"
  val akkaHttp    = "10.0.9"
  val finagle     = "6.45.0"
  val twitterUtil = "6.45.0"
  val finch       = "0.15.1"
  val slf4j       = "1.7.25"
  val jackson     = "2.8.4"
}

val logback           = "ch.qos.logback"                  %  "logback-classic"        % "1.2.3"
val slf4j = new {
  val api      = "org.slf4j"           %   "slf4j-api"             % V.slf4j
  val jcl      = "org.slf4j"           %   "jcl-over-slf4j"        % V.slf4j
  val log4j    = "org.slf4j"           %   "log4j-over-slf4j"      % V.slf4j
  val jul      = "org.slf4j"           %   "jul-to-slf4j"          % V.slf4j
}


libraryDependencies ++= {
  Seq(
    "com.typesafe.akka"      %% "akka-http"                 % "10.0.9",
    "com.typesafe.akka"      %% "akka-slf4j"                % V.akka,
    "com.typesafe.akka"      %% "akka-stream"               % V.akka,
    "software.amazon.awssdk" %  "s3"                        % "2.0.0-preview-1",
    "software.amazon.awssdk" %  "aws-http-nio-client-netty" % "2.0.0-preview-1",
    slf4j.api,
    slf4j.jcl,
    slf4j.jul,
    logback
  )
}

