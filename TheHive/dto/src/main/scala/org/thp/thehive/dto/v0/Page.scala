package org.thp.thehive.dto.v0

import play.api.libs.json._

import java.util.Date

case class InputPage(
    title: String,
    content: String,
    order: Option[Int],
    category: String
)

object InputPage {
  implicit val writes: OWrites[InputPage] = Json.writes[InputPage]
}

case class OutputPage(
    _id: String,
    id: String,
    createdBy: String,
    updatedBy: Option[String] = None,
    createdAt: Date,
    updatedAt: Option[Date] = None,
    title: String,
    content: String,
    _type: String,
    slug: String,
    order: Int,
    category: String
)

object OutputPage {
  implicit val format: OFormat[OutputPage] = Json.format[OutputPage]
}
