package org.thp.thehive.services.th3

import org.apache.tinkerpop.gremlin.process.traversal.Order
import org.joda.time.DateTime
import org.scalactic.Accumulation._
import org.scalactic._
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.controllers._
import org.thp.scalligraph.query.{Aggregation, InputQuery, PublicProperties}
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal._
import org.thp.scalligraph.{BadRequestError, InvalidFormatAttributeError}
import play.api.Logger
import play.api.libs.json._

import java.lang.{Long => JLong}
import java.time.temporal.ChronoUnit
import java.util.{Calendar, Date, List => JList}
import scala.reflect.runtime.{universe => ru}
import scala.util.Try
import scala.util.matching.Regex

object TH3Aggregation {

  object AggObj {
    def unapply(field: Field): Option[(String, FObject)] =
      field match {
        case f: FObject =>
          f.get("_agg") match {
            case FString(name) => Some(name -> (f - "_agg"))
            case _             => None
          }
        case _ => None
      }
  }

  val intervalParser: FieldsParser[(Long, ChronoUnit)] = FieldsParser[(Long, ChronoUnit)]("interval") {
    case (_, f) =>
      withGood(
        FieldsParser.long.optional.on("_interval")(f),
        FieldsParser[ChronoUnit]("chronoUnit") {
          case (_, f @ FString(value)) =>
            Or.from(
              Try(ChronoUnit.valueOf(value)).toOption,
              One(InvalidFormatAttributeError("_unit", "chronoUnit", ChronoUnit.values.toSet.map((_: ChronoUnit).toString), f))
            )
        }.on("_unit")(f)
      )((i, u) => i.getOrElse(0L) -> u)
  }

  val intervalRegex: Regex = "(\\d+)([smhdwMy])".r

  val mergedIntervalParser: FieldsParser[(Long, ChronoUnit)] = FieldsParser[(Long, ChronoUnit)]("interval") {
    case (_, FString(intervalRegex(interval, unit))) =>
      Good(unit match {
        case "s" => interval.toLong -> ChronoUnit.SECONDS
        case "m" => interval.toLong -> ChronoUnit.MINUTES
        case "h" => interval.toLong -> ChronoUnit.HOURS
        case "d" => interval.toLong -> ChronoUnit.DAYS
        case "w" => interval.toLong -> ChronoUnit.WEEKS
        case "M" => interval.toLong -> ChronoUnit.MONTHS
        case "y" => interval.toLong -> ChronoUnit.YEARS
      })
  }

  def aggregationFieldParser(
      filterParser: FieldsParser[InputQuery[Traversal.Unk, Traversal.Unk]]
  ): PartialFunction[String, FieldsParser[Aggregation]] = {
    case "field" =>
      FieldsParser("FieldAggregation") {
        case (_, field) =>
          withGood(
            FieldsParser.string.optional.on("_name")(field),
            FieldsParser.string.on("_field")(field),
            FieldsParser.string.sequence.on("_order")(field).orElse(FieldsParser.string.on("_order").map("order")(Seq(_))(field)),
            FieldsParser.long.optional.on("_size")(field),
            fieldsParser(filterParser).sequence.on("_select")(field),
            filterParser.optional.on("_query")(field)
          )((aggName, fieldName, order, size, subAgg, filter) => FieldAggregation(aggName, fieldName, order, size, subAgg, filter))
      }
    case "count" =>
      FieldsParser("CountAggregation") {
        case (_, field) =>
          withGood(FieldsParser.string.optional.on("_name")(field), filterParser.optional.on("_query")(field))((aggName, filter) =>
            AggCount(aggName, filter)
          )
      }
    case "time" =>
      FieldsParser("TimeAggregation") {
        case (_, field) =>
          withGood(
            FieldsParser.string.optional.on("_name")(field),
            FieldsParser
              .string
              .sequence
              .on("_fields")(field)
              .orElse(FieldsParser.string.on("_fields")(field).map(Seq(_))), //.map("toSeq")(f => Good(Seq(f)))),
            mergedIntervalParser.on("_interval").orElse(intervalParser)(field),
            fieldsParser(filterParser).sequence.on("_select")(field),
            filterParser.optional.on("_query")(field)
          ) { (aggName, fieldNames, intervalUnit, subAgg, filter) =>
            if (fieldNames.lengthCompare(1) > 0)
              logger.warn(s"Only one field is supported for time aggregation (aggregation $aggName, ${fieldNames.tail.mkString(",")} are ignored)")
            TimeAggregation(aggName, fieldNames.head, intervalUnit._1, intervalUnit._2, subAgg, filter)
          }
      }
    case "avg" =>
      FieldsParser("AvgAggregation") {
        case (_, field) =>
          withGood(
            FieldsParser.string.optional.on("_name")(field),
            FieldsParser.string.on("_field")(field),
            filterParser.optional.on("_query")(field)
          )((aggName, fieldName, filter) => AggAvg(aggName, fieldName, filter))
      }
    case "min" =>
      FieldsParser("MinAggregation") {
        case (_, field) =>
          withGood(
            FieldsParser.string.optional.on("_name")(field),
            FieldsParser.string.on("_field")(field),
            filterParser.optional.on("_query")(field)
          )((aggName, fieldName, filter) => AggMin(aggName, fieldName, filter))
      }
    case "max" =>
      FieldsParser("MaxAggregation") {
        case (_, field) =>
          withGood(
            FieldsParser.string.optional.on("_name")(field),
            FieldsParser.string.on("_field")(field),
            filterParser.optional.on("_query")(field)
          )((aggName, fieldName, filter) => AggMax(aggName, fieldName, filter))
      }
    case "sum" =>
      FieldsParser("SumAggregation") {
        case (_, field) =>
          withGood(
            FieldsParser.string.optional.on("_name")(field),
            FieldsParser.string.on("_field")(field),
            filterParser.optional.on("_query")(field)
          )((aggName, fieldName, filter) => AggSum(aggName, fieldName, filter))
      }
    case other =>
      new FieldsParser[Aggregation](
        "unknownAttribute",
        Set.empty,
        {
          case (path, _) =>
            Bad(One(InvalidFormatAttributeError(path.toString, "string", Set("field", "time", "count", "avg", "min", "max"), FString(other))))
        }
      )
  }

  def fieldsParser(filterParser: FieldsParser[InputQuery[Traversal.Unk, Traversal.Unk]]): FieldsParser[Aggregation] =
    FieldsParser("aggregation") {
      case (_, AggObj(name, field)) => aggregationFieldParser(filterParser)(name)(field)
    }
}

case class AggSum(aggName: Option[String], fieldName: String, filter: Option[InputQuery[Traversal.Unk, Traversal.Unk]])
    extends Aggregation(aggName.getOrElse(s"sum_$fieldName")) {
  override def getTraversal(
      publicProperties: PublicProperties,
      traversalType: ru.Type,
      traversal: Traversal.Unk,
      authContext: AuthContext
  ): Traversal.Domain[Output[_]] = {
    val fieldPath = FPath(fieldName)
    val property = publicProperties
      .get[Traversal.UnkD, Traversal.UnkDU](fieldPath, traversalType)
      .getOrElse(throw BadRequestError(s"Property $fieldName for type $traversalType not found"))
    filter
      .fold(traversal)(_(publicProperties, traversalType, traversal, authContext))
      .fold
      .coalesce(
        t =>
          property
            .select(fieldPath, t.unfold, authContext)
            .sum
            .domainMap(sum => Output(Json.obj(name -> JsNumber(BigDecimal(sum.toString)))))
            .castDomain[Output[_]],
        Output(Json.obj(name -> JsNull))
      )
  }
}

case class AggAvg(aggName: Option[String], fieldName: String, filter: Option[InputQuery[Traversal.Unk, Traversal.Unk]])
    extends Aggregation(aggName.getOrElse(s"sum_$fieldName")) {
  override def getTraversal(
      publicProperties: PublicProperties,
      traversalType: ru.Type,
      traversal: Traversal.Unk,
      authContext: AuthContext
  ): Traversal.Domain[Output[_]] = {
    val fieldPath = if (fieldName.startsWith("computed")) FPathElem(fieldName) else FPath(fieldName)
    val property = publicProperties
      .get[Traversal.UnkD, Traversal.UnkDU](fieldPath, traversalType)
      .getOrElse(throw BadRequestError(s"Property $fieldName for type $traversalType not found"))
    filter
      .fold(traversal)(_(publicProperties, traversalType, traversal, authContext))
      .fold
      .coalesce(
        t =>
          property
            .select(fieldPath, t.unfold, authContext)
            .mean
            .domainMap(avg => Output(Json.obj(name -> avg)))
            .asInstanceOf[Traversal.Domain[Output[_]]],
        Output(Json.obj(name -> JsNull))
      )
  }
}

case class AggMin(aggName: Option[String], fieldName: String, filter: Option[InputQuery[Traversal.Unk, Traversal.Unk]])
    extends Aggregation(aggName.getOrElse(s"min_$fieldName")) {
  override def getTraversal(
      publicProperties: PublicProperties,
      traversalType: ru.Type,
      traversal: Traversal.Unk,
      authContext: AuthContext
  ): Traversal.Domain[Output[_]] = {
    val fieldPath = FPath(fieldName)
    val property = publicProperties
      .get[Traversal.UnkD, Traversal.UnkDU](fieldPath, traversalType)
      .getOrElse(throw BadRequestError(s"Property $fieldName for type $traversalType not found"))
    filter
      .fold(traversal)(_(publicProperties, traversalType, traversal, authContext))
      .fold
      .coalesce(
        t =>
          property
            .select(fieldPath, t.unfold, authContext)
            .min
            .domainMap(min => Output(Json.obj(name -> property.toJson(min)))),
        Output(Json.obj(name -> JsNull))
      )
  }
}

case class AggMax(aggName: Option[String], fieldName: String, filter: Option[InputQuery[Traversal.Unk, Traversal.Unk]])
    extends Aggregation(aggName.getOrElse(s"max_$fieldName")) {
  override def getTraversal(
      publicProperties: PublicProperties,
      traversalType: ru.Type,
      traversal: Traversal.Unk,
      authContext: AuthContext
  ): Traversal.Domain[Output[_]] = {
    val fieldPath = FPath(fieldName)
    val property = publicProperties
      .get[Traversal.UnkD, Traversal.UnkDU](fieldPath, traversalType)
      .getOrElse(throw BadRequestError(s"Property $fieldName for type $traversalType not found"))
    filter
      .fold(traversal)(_(publicProperties, traversalType, traversal, authContext))
      .fold
      .coalesce(
        t =>
          property
            .select(fieldPath, t.unfold, authContext)
            .max
            .domainMap(max => Output(Json.obj(name -> property.toJson(max)))),
        Output(Json.obj(name -> JsNull))
      )
  }
}

case class AggCount(aggName: Option[String], filter: Option[InputQuery[Traversal.Unk, Traversal.Unk]])
    extends Aggregation(aggName.getOrElse("count")) {
  override def getTraversal(
      publicProperties: PublicProperties,
      traversalType: ru.Type,
      traversal: Traversal.Unk,
      authContext: AuthContext
  ): Traversal.Domain[Output[_]] =
    filter
      .fold(traversal)(_(publicProperties, traversalType, traversal, authContext))
      .count
      .domainMap(count => Output(Json.obj(name -> count)))
      .castDomain[Output[_]]
}

//case class AggTop[T](fieldName: String) extends AggFunction[T](s"top_$fieldName")

case class FieldAggregation(
    aggName: Option[String],
    fieldName: String,
    orders: Seq[String],
    size: Option[Long],
    subAggs: Seq[Aggregation],
    filter: Option[InputQuery[Traversal.Unk, Traversal.Unk]]
) extends Aggregation(aggName.getOrElse(s"field_$fieldName")) {
  lazy val logger: Logger = Logger(getClass)

  override def getTraversal(
      publicProperties: PublicProperties,
      traversalType: ru.Type,
      traversal: Traversal.Unk,
      authContext: AuthContext
  ): Traversal.Domain[Output[_]] = {
    val label     = StepLabel[Traversal.UnkD, Traversal.UnkG, Converter[Traversal.UnkD, Traversal.UnkG]]
    val fieldPath = FPath(fieldName)
    val property = publicProperties
      .get[Traversal.UnkD, Traversal.UnkDU](fieldPath, traversalType)
      .getOrElse(throw BadRequestError(s"Property $fieldName for type $traversalType not found"))
    val groupedVertices = property
      .select(
        fieldPath,
        filter
          .fold(traversal)(_(publicProperties, traversalType, traversal, authContext))
          .as(label),
        authContext
      )
      .group(_.by, _.by(_.select(label).fold))
      .unfold
    val sortedAndGroupedVertex = orders
      .map {
        case order if order.headOption.contains('-') => order.tail -> Order.desc
        case order if order.headOption.contains('+') => order.tail -> Order.asc
        case order                                   => order      -> Order.asc
      }
      .foldLeft(groupedVertices) {
        case (acc, (field, order)) if field == fieldName                    => acc.sort(_.by(_.selectKeys, order))
        case (acc, (field, order)) if field == "count" || field == "_count" => acc.sort(_.by(_.selectValues.localCount, order))
        case (acc, (field, _)) =>
          logger.warn(s"In field aggregation you can only sort by the field ($fieldName) or by count, not by $field")
          acc
      }
    val sizedSortedAndGroupedVertex = size.fold(sortedAndGroupedVertex)(sortedAndGroupedVertex.limit)
    val subAggProjection = subAggs.map {
      agg => (s: GenericBySelector[Seq[Traversal.UnkD], JList[Traversal.UnkG], Converter.CList[Traversal.UnkD, Traversal.UnkG, Converter[
        Traversal.UnkD,
        Traversal.UnkG
      ]]]) =>
        s.by(t => agg.getTraversal(publicProperties, traversalType, t.unfold, authContext).castDomain[Output[_]])
    }

    sizedSortedAndGroupedVertex
      .project(
        _.by(_.selectKeys)
          .by(
            _.selectValues
              .flatProject(subAggProjection: _*)
              .domainMap { aggResult =>
                Output(
                  aggResult
                    .asInstanceOf[Seq[Output[JsObject]]]
                    .map(_.toValue)
                    .reduceOption(_ deepMerge _)
                    .getOrElse(JsObject.empty)
                )
              }
          )
      )
      .fold
      .domainMap(kvs =>
        Output(JsObject(kvs.map {
          case (JsString(k), v) => k          -> v.toJson
          case (k, v)           => k.toString -> v.toJson
        }))
      )
      .castDomain[Output[_]]
  }
}

case class TimeAggregation(
    aggName: Option[String],
    fieldName: String,
    interval: Long,
    unit: ChronoUnit,
    subAggs: Seq[Aggregation],
    filter: Option[InputQuery[Traversal.Unk, Traversal.Unk]]
) extends Aggregation(aggName.getOrElse(fieldName)) {

  private val threeDaysInMillis = 259200000L
  private val oneWeekInMillis   = 604800000L
  private def roundToWeek(date: Date, nWeek: Long): Long = {
    val shiftedDate = date.getTime + threeDaysInMillis // Jan 1st is a thursday
    shiftedDate - (shiftedDate % (oneWeekInMillis * nWeek)) - threeDaysInMillis
  }

  private def dateToKey(date: Date): Long =
    unit match {
      case ChronoUnit.WEEKS => roundToWeek(date, interval)
      case ChronoUnit.MONTHS =>
        val d = new DateTime(date)
        new DateTime(d.getYear, d.getMonthOfYear, 1, 0, 0).getMillis
      case ChronoUnit.YEARS =>
        val d = new DateTime(date)
        new DateTime(d.getYear, 1, 1, 0, 0).getMillis
      case other =>
        val duration = other.getDuration.toMillis * interval
        date.getTime - (date.getTime % duration)
    }

  def keyToDate(key: Long): Date = new Date(key)

  override def getTraversal(
      publicProperties: PublicProperties,
      traversalType: ru.Type,
      traversal: Traversal.Unk,
      authContext: AuthContext
  ): Traversal.Domain[Output[_]] = {
    val fieldPath = FPath(fieldName)
    val property = publicProperties
      .get[Traversal.UnkD, Traversal.UnkDU](fieldPath, traversalType)
      .getOrElse(throw BadRequestError(s"Property $fieldName for type $traversalType not found"))
    val label = StepLabel[Traversal.UnkD, Traversal.UnkG, Converter[Traversal.UnkD, Traversal.UnkG]]
    val groupedVertex = property
      .select(
        fieldPath,
        filter
          .fold(traversal)(_(publicProperties, traversalType, traversal, authContext))
          .as(label),
        authContext
      )
      .cast[Date, Date]
      .graphMap[Long, JLong, Converter[Long, JLong]](dateToKey, Converter.long)
      .group(_.by, _.by(_.select(label).fold))
      .unfold
    val subAggProjection = subAggs.map {
      agg => (s: GenericBySelector[
        Seq[Traversal.UnkD],
        JList[Traversal.UnkG],
        Converter.CList[Traversal.UnkD, Traversal.UnkG, Converter[Traversal.UnkD, Traversal.UnkG]]
      ]) =>
        s.by(t => agg.getTraversal(publicProperties, traversalType, t.unfold, authContext).castDomain[Output[_]])
    }

    groupedVertex
      .project(
        _.by(_.selectKeys)
          .by(
            _.selectValues
              .flatProject(subAggProjection: _*)
              .domainMap { aggResult =>
                Output(
                  aggResult
                    .asInstanceOf[Seq[Output[JsObject]]]
                    .map(_.toValue)
                    .reduceOption(_ deepMerge _)
                    .getOrElse(JsObject.empty)
                )
              }
          )
      )
      .fold
      .domainMap(kvs => Output(JsObject(kvs.map(kv => kv._1.toString -> Json.obj(name -> kv._2.toJson)))))
      .castDomain[Output[_]]
  }
}
