package cromwell.filesystems.drs

import java.io.ByteArrayInputStream
import java.nio.channels.ReadableByteChannel

import akka.actor.ActorSystem
import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO
import cloud.nio.impl.drs.{DrsCloudNioFileSystemProvider, GcsFilePath, MarthaResponse, Url}
import com.google.api.services.oauth2.Oauth2Scopes
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.StorageOptions
import com.typesafe.config.Config
import cromwell.cloudsupport.gcp.GoogleConfiguration
import cromwell.core.WorkflowOptions
import cromwell.core.path.{PathBuilder, PathBuilderFactory}
import org.apache.http.impl.client.HttpClientBuilder

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}


/**
  * Cromwell Wrapper around DrsFileSystems to load the configuration.
  * This class is used as the global configuration class in the drs filesystem
  */
class DrsFileSystemConfig(val config: Config)


class DrsPathBuilderFactory(globalConfig: Config, instanceConfig: Config, singletonConfig: DrsFileSystemConfig) extends PathBuilderFactory {

  private lazy val googleConfiguration: GoogleConfiguration = GoogleConfiguration(globalConfig)
  private lazy val scheme = instanceConfig.getString("auth")
  private lazy val googleAuthMode = googleConfiguration.auth(scheme) match {
    case Valid(auth) => auth
    case Invalid(error) => throw new RuntimeException(s"Error while instantiating DRS path builder factory. Errors: ${error.toString}")
  }

  private lazy val httpClientBuilder = HttpClientBuilder.create()

  private val GcsScheme = "gs"

  private def extractUrlForScheme(drsPath: String, urlArray: Array[Url], scheme: String): Either[UrlNotFoundException, String] = {
    val schemeUrlOption = urlArray.find(_.url.startsWith(scheme))

    schemeUrlOption match {
      case Some(schemeUrl) => Right(schemeUrl.url)
      case None => Left(UrlNotFoundException(drsPath, scheme))
    }
  }


  private def gcsInputStream(gcsFile: GcsFilePath, serviceAccount: String): IO[ReadableByteChannel] = {
    val credentials = GoogleCredentials.fromStream(new ByteArrayInputStream(serviceAccount.getBytes()))
    val storage = StorageOptions.newBuilder().setCredentials(credentials).build().getService

    IO.delay {
      val blob = storage.get(gcsFile.bucket, gcsFile.file)
      blob.reader()
    }
  }


  private def inputReadChannel(url: String, urlScheme: String, serviceAccount: String): IO[ReadableByteChannel] =  {
    urlScheme match {
      case GcsScheme => {
        val Array(bucket, fileToBeLocalized) = url.replace(s"$GcsScheme://", "").split("/", 2)
        gcsInputStream(GcsFilePath(bucket, fileToBeLocalized), serviceAccount)
      }
      case otherScheme => IO.raiseError(new UnsupportedOperationException(s"DRS currently doesn't support reading files for $otherScheme."))
    }
  }


  private def drsReadInterpreter(drsPath: String, marthaResponse: MarthaResponse): IO[ReadableByteChannel] = {
    val serviceAccount = marthaResponse.googleServiceAccount match {
      case Some(googleSA) => googleSA.data.toString
      case None => throw GoogleSANotFoundException(drsPath)
    }

    //Currently, Martha only supports resolving DRS paths to GCS paths
    extractUrlForScheme(drsPath, marthaResponse.dos.data_object.urls, GcsScheme) match {
      case Right(url) => inputReadChannel(url, GcsScheme, serviceAccount)
      case Left(e) => IO.raiseError(e)
    }
  }


  override def withOptions(options: WorkflowOptions)(implicit as: ActorSystem, ec: ExecutionContext): Future[PathBuilder] = {
    val marthaScopes = List(
      // Profile and Email scopes are requirements for interacting with Martha v2
      Oauth2Scopes.USERINFO_EMAIL,
      Oauth2Scopes.USERINFO_PROFILE
    ).asJavaCollection
    val authCredentials = googleAuthMode.credentials((key: String) => options.get(key).get, marthaScopes)

    Future.successful(DrsPathBuilder(new DrsCloudNioFileSystemProvider(singletonConfig.config, authCredentials, httpClientBuilder, drsReadInterpreter)))
  }
}



case class GoogleSANotFoundException(drsPath: String) extends Exception(s"Error finding Google Service Account associated with DRS path $drsPath through Martha.")

case class UrlNotFoundException(drsPath: String, scheme: String) extends Exception(s"DRS was not able to find a $scheme url associated with $drsPath.")
