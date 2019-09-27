package org.thp.thehive.controllers.v0

import java.util.Date

import io.scalaland.chimney.dsl._
import org.thp.scalligraph.controllers.Output
import org.thp.scalligraph.models.Entity
import org.thp.thehive.dto.v0.{InputCustomField, InputCustomFieldValue, OutputCustomField, OutputCustomFieldValue}
import org.thp.thehive.models.{CustomField, CustomFieldString, CustomFieldWithValue}

import scala.language.implicitConversions

object CustomFieldConversion {

  def fromInputCustomField(inputCustomFieldValue: InputCustomFieldValue): (String, Option[Any]) =
    inputCustomFieldValue.name -> inputCustomFieldValue.value

  implicit def toOutputCustomField(customFieldValue: CustomFieldWithValue): Output[OutputCustomFieldValue] =
    Output[OutputCustomFieldValue](
      customFieldValue
        .into[OutputCustomFieldValue]
        .withFieldComputed(_.value, _.value.map {
          case d: Date => d.getTime.toString
          case other   => other.toString
        })
        .withFieldComputed(_.tpe, _.typeName)
        .transform
    )

  implicit def fromInputCField(inputCustomField: InputCustomField): CustomField =
    inputCustomField
      .into[CustomField]
      .withFieldComputed(_.`type`, icf => CustomField.fromString(icf.name).getOrElse(CustomFieldString))
      .withFieldComputed(_.mandatory, _.mandatory.getOrElse(false))
      .withFieldComputed(_.description, _.description)
      .withFieldComputed(_.name, _.name)
      .transform

  implicit def toOutputCustomField(customField: CustomField with Entity): Output[OutputCustomField] =
    Output[OutputCustomField](
      customField
        .asInstanceOf[CustomField]
        .into[OutputCustomField]
        .withFieldComputed(_.`type`, _.`type`.name)
        .withFieldRenamed(_.name, _.reference)
        .withFieldConst(_.options, Nil)
        .withFieldComputed(_.mandatory, _.mandatory)
        .transform
    )
}
