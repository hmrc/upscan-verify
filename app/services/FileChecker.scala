package services

import model.{FileCheckingResult, S3ObjectLocation}

import scala.concurrent.Future

trait FileChecker {
  def scan(
    location: S3ObjectLocation,
    objectContent: ObjectContent,
    objectMetadata: ObjectMetadata): Future[FileCheckingResult]
}
