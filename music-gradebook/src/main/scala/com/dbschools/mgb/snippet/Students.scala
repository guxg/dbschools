package com.dbschools.mgb
package snippet

import xml.{NodeSeq, Text}
import scalaz._
import Scalaz._
import org.scala_tools.time.Imports._
import org.joda.time.Days
import org.squeryl.PrimitiveTypeMode._
import net.liftweb._
import common.{Full, Loggable}
import util._
import Helpers._
import net.liftweb.http.{SessionVar, Templates, SHtml}
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsCmds.Replace
import bootstrap.liftweb.{Actors, ApplicationPaths}
import schema.{Musician, AppSchema}
import model.BoxOpener._
import com.dbschools.mgb.model.{Cache, LastPassFinder, Terms, GroupAssignments}
import com.dbschools.mgb.comet.{ScheduleMusicians, ScheduledMusician, TestCometDispatcher, ClearSchedule}

object SortBy extends Enumeration {
  type SortBy = Value
  val Name = Value("Name")
  val LastAssessment = Value("Last Assessment")
  val LastPiece = Value("Last Piece")
}
object sortingBy extends SessionVar[SortBy.Value](SortBy.Name)

object svSelectors extends SessionVar[Selectors](new Selectors())

class Students extends Loggable {
  private val selectors = svSelectors.is

  private def replaceContents = {
    val template = "_inGroupsTable"
    Templates(List(template)).map(Replace("inGroups", _)).open
  }

  selectors.opCallback = Some(() => replaceContents)
  def yearSelector = selectors.yearSelector
  def groupSelector = selectors.groupSelector
  def instrumentSelector = selectors.instrumentSelector

  private val lastPassFinder = new LastPassFinder()

  def createNew = "#create [href]" #> ApplicationPaths.newStudent.href

  def sortBy = SHtml.ajaxRadio[SortBy.Value](Seq(SortBy.Name, SortBy.LastAssessment, SortBy.LastPiece), Full(sortingBy.is),
    (s) => {sortingBy(s); replaceContents}).flatMap(c => <span>{c.xhtml} {c.key.toString} </span>)

  var newId = 0
  var grade = 6
  var name = ""
  var selectedMusicians = Map[Int, Musician]()

  def newStudent = {

    def saveStudent = {
      logger.warn(s"Creating student $newId $grade $name")
      Noop
    }

    "#studentId" #> SHtml.text(if (newId == 0) "" else newId.toString,
                      id => Helpers.asInt(id).foreach(intId => newId = intId)) &
    "#grade"     #> SHtml.number(grade, grade = _, grade, 8) &
    "#name"      #> SHtml.text(name, name = _) &
    "#save"      #> SHtml.onSubmitUnit(() => saveStudent)
  }

  private val lastAssTimeByMusician = (for {
    gm <- from(AppSchema.assessments)(a => groupBy(a.musician_id) compute max(a.assessment_time))
    m <- gm.measures
  } yield gm.key -> new DateTime(m.getTime)).toMap

  def inGroups = {
    val fmt = DateTimeFormat.forStyle("S-")
    val lastPassesByMusician = lastPassFinder.lastPassed().groupBy(_.musicianId)
    
    def scheduleButton= {
      SHtml.ajaxButton("Schedule for Testing", () => {
        val now = DateTime.now
        val scheduledMusicians = selectedMusicians.values.map(musician => {
          val lastAsmtTime = lastAssTimeByMusician.get(musician.id)
          val days = lastAsmtTime.map(la => Days.daysBetween(la, now).getDays) | Int.MaxValue
          val opNextPieceName = for {
            lastPass  <- lastPassFinder.lastPassed(Some(musician.id)).headOption
            piece     <- Cache.pieces.find(_.id == lastPass.pieceId)
            nextPiece = lastPassFinder.next(piece)
          } yield nextPiece.name.get
          ScheduledMusician(musician, -days, opNextPieceName | Cache.pieces.head.name.get)
        })
        Actors.testScheduler ! ScheduleMusicians(scheduledMusicians)
        Noop
      })
    }

    def clearScheduleButton = SHtml.ajaxButton("Clear Testing", () => {
      Actors.testScheduler ! ClearSchedule
      Noop
    })
    
    (if (selectors.opSelectedTerm   .isDefined) ".schYear" #> none[String] else PassThru) andThen (
    (if (selectors.opSelectedGroupId.isDefined) ".group"   #> none[String] else PassThru) andThen (
    (if (selectors.opSelectedInstId .isDefined) ".instr"   #> none[String] else PassThru) andThen (

    "#clearSchedule"  #> clearScheduleButton &
    "#schedule"       #> scheduleButton &
    ".studentRow"     #> {
      val longAgo = new DateTime("1000-01-01").toDate

      val byYear = GroupAssignments(None, svSelectors.opSelectedTerm, svSelectors.opSelectedGroupId,
        svSelectors.opSelectedInstId).toSeq.sortBy(_.musicianGroup.school_year)

      val fullySorted = sortingBy.is match {
        case SortBy.Name =>
          byYear.sortBy(_.musician.name)
        case SortBy.LastAssessment =>
          byYear.sortBy(ga => lastAssTimeByMusician.get(ga.musician.id).map(_.toDate) | longAgo)
        case SortBy.LastPiece =>
          def pos(id: Int) =
            lastPassesByMusician.get(id).toList.flatten.sortBy(-_.testOrder).lastOption.map(_.testOrder) | 0
          byYear.sortBy(ga => -pos(ga.musician.id))
      }

      def selectionCheckbox(musician: Musician) =
        SHtml.ajaxCheckbox(false, checked => {
          if (checked) selectedMusicians += musician.id -> musician
          else selectedMusicians -= musician.id
          if (selectedMusicians.isEmpty) JsHideId("schedule") else JsShowId("schedule")
        })

      val now = DateTime.now
      fullySorted.map(row => {
        val lastAsmtTime = lastAssTimeByMusician.get(row.musician.id)
        ".sel      *" #> selectionCheckbox(row.musician) &
        ".schYear  *" #> Terms.formatted(row.musicianGroup.school_year) &
        ".stuName  *" #> studentLink(row.musician) &
        ".grade    *" #> Terms.graduationYearAsGrade(row.musician.graduation_year.get) &
        ".group    *" #> row.group.name &
        ".instr    *" #> row.instrument.name.get &
        ".lastAss  *" #> ~lastAsmtTime.map(fmt.print) &
        ".daysSince *" #> ~lastAsmtTime.map(la => Days.daysBetween(la, now).getDays.toString) &
        ".lastPass *" #> formatLastPasses(lastPassesByMusician.get(row.musician.id))
      })
    })))
  }

  private def formatLastPasses(opLastPasses: Option[Iterable[LastPassFinder#LastPass]]): NodeSeq = {
    val lastPasses = opLastPasses.getOrElse(Seq[LastPassFinder#LastPass]()).map(lp => Text(lp.toString))
    lastPasses.fold(NodeSeq.Empty)(_ ++ <br/> ++ _).drop(1)
  }

  def inNoGroups = {
    val musicians = join(AppSchema.musicians, AppSchema.musicianGroups.leftOuter)((m, mg) =>
      where(mg.map(_.id).isNull) select m on (m.musician_id.get === mg.map(_.musician_id)))

    ".studentRow"   #> musicians.map(m =>
      ".stuName  *" #> studentLink(m) &
      ".id       *" #> m.id &
      ".stuId    *" #> m.student_id.get &
      ".grade    *" #> Terms.graduationYearAsGrade(m.graduation_year.get)
    )
  }

  private def studentLink(m: Musician) = SHtml.link(Students.urlToDetails(m), () => {}, Text(m.name))
}

object Students {
  def urlToDetails(m: Musician) = "studentDetails?id=" + m.id
}