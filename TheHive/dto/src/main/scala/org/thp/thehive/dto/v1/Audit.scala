package org.thp.thehive.dto.v1

import org.thp.scalligraph.models.Entity
import play.api.libs.json.{Json, OFormat}

import java.util.Date

case class OutputEntity(_type: String, _id: String, _createdAt: Date, _createdBy: String, _updatedAt: Option[Date], _updatedBy: Option[String])

object OutputEntity {
  implicit val format: OFormat[OutputEntity] = Json.format[OutputEntity]

  def apply(e: Entity): OutputEntity =
    OutputEntity(
      e._label,
      e._id.toString,
      e._createdAt,
      e._createdBy,
      e._updatedAt,
      e._updatedBy
    )
}

case class OutputAudit(
    _id: String,
    _type: String,
    _createdBy: String,
    _updatedBy: Option[String] = None,
    _createdAt: Date,
    _updatedAt: Option[Date] = None,
    operation: String,
    requestId: String,
    attributeName: Option[String],
    oldValue: Option[String],
    newValue: Option[String],
    obj: Option[OutputEntity],
    summary: Map[String, Map[String, Int]]
)

object OutputAudit {
  implicit val format: OFormat[OutputAudit] = Json.format[OutputAudit]
}
