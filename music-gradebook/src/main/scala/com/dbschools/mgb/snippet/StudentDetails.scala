package com.dbschools.mgb
package snippet

import java.sql.Timestamp
import xml.Text
import scalaz._
import Scalaz._
import org.squeryl.PrimitiveTypeMode._
import org.scala_tools.time.Imports._
import net.liftweb._
import net.liftweb.common.{Empty, Full, Loggable}
import util._
import http._
import js.JsCmds._
import Helpers._
import js.JsCmds.Confirm
import net.liftweb.http.js.JsCmds.SetHtml
import model._
import model.{GroupAssignment, TagCounts}
import com.dbschools.mgb.schema.{AssessmentTag, AppSchema, Musician, Assessment, MusicianGroup, Piece}

class StudentDetails extends TagCounts with Loggable {
  private var selectedMusicianGroups = Map[Int, MusicianGroup]()
  private var opMusician = none[Musician]

  case class MusicianDetails(musician: Musician, groups: Iterable[GroupAssignment], assessments: Iterable[Assessment])

  def render = {

    opMusician = for {
      musicianId  <- S.param("id").flatMap(asInt).toOption
      musician    <- AppSchema.musicians.lookup(musicianId)
    } yield musician

    val opMusicianDetails = opMusician.map(musician =>
      MusicianDetails(musician, GroupAssignments(Some(musician.musician_id.is)),
      AppSchema.assessments.where(_.musician_id === musician.musician_id.is).toSeq))

    def changeName(musician: Musician, field: record.field.StringField[Musician], id: String)(t: String) = {
      field(t)
      if (musician.validate.isEmpty) {
        AppSchema.musicians.update(musician)
        SetHtml(id, Text(t))
      } else Noop
    }

    def changeStuId(musician: Musician)(t: String) =
      Helpers.asInt(t).toOption.map(newId => {
        musician.student_id(newId)
        AppSchema.musicians.update(musician)
        SetHtml("stuId", Text(newId.toString))
      }) getOrElse Noop

    def assessmentsSummary(md: MusicianDetails) = {
      val (pass, fail) = md.assessments.partition(_.pass)
      val tagCountsStr = tagCounts(md.musician.id) match {
        case Nil => ""
        case n => n.map(tc => s"${tc.tag}: ${tc.count}").mkString(", comments: ", ", ", "")
      }
      s"Passes: ${pass.size}, failures: ${fail.size}$tagCountsStr"
    }

    def makeDetails(md: MusicianDetails) =
      ".lastName *"       #> SHtml.swappable(<span id="lastName">{md.musician.last_name.is}</span>,
                                SHtml.ajaxText(md.musician.last_name.is,
                                changeName(md.musician, md.musician.last_name, "lastName"))) &
      ".firstName *"      #> SHtml.swappable(<span id="firstName">{md.musician.first_name.is}</span>,
                                SHtml.ajaxText(md.musician.first_name.is,
                                changeName(md.musician, md.musician.first_name, "firstName"))) &
      ".grade"            #> Terms.graduationYearAsGrade(md.musician.graduation_year.is) &
      ".stuId"            #> SHtml.swappable(<span id="stuId">{md.musician.student_id.toString}</span>,
                                SHtml.ajaxText(md.musician.student_id.toString, changeStuId(md.musician))) &
      ".mgbId"            #> md.musician.musician_id &
      ".lastPiece"        #> new LastPassFinder().lastPassed(Some(md.musician.musician_id.is)).mkString(", ") &
      ".assignmentRow *"  #> groupsTable(md.groups) &
      ".assessmentsSummary *" #> assessmentsSummary(md)

    "#student" #> opMusicianDetails.map(makeDetails)
  }

  private def groupsTable(groups: Iterable[GroupAssignment]) =
    groups.map(ga =>
      ".sel *"        #> assignmentCheckbox(ga) &
      ".year *"       #> Terms.formatted(ga.musicianGroup.school_year) &
      ".group *"      #> groupSelector(ga) &
      ".instrument *" #> instrumentSelector(ga)
    )

  private def assignmentCheckbox(ga: GroupAssignment) =
    SHtml.ajaxCheckbox(false, checked => {
      if (checked) selectedMusicianGroups += ga.musicianGroup.id -> ga.musicianGroup
      else selectedMusicianGroups -= ga.musicianGroup.id
      if (selectedMusicianGroups.isEmpty) JsHideId("delete") else JsShowId("delete")
    })

  private def groupSelector(ga: GroupAssignment) =
    SHtml.ajaxSelect(Cache.groups.map(g => (g.id.toString, g.name)).toSeq,
      Full(ga.musicianGroup.group_id.toString), gid => {
        AppSchema.musicianGroups.update(mg => where(mg.id === ga.musicianGroup.id)
          set (mg.group_id := gid.toInt))
        Noop
      })

  private def instrumentSelector(ga: GroupAssignment) =
    SHtml.ajaxSelect(Cache.instruments.map(i => (i.id.toString, i.name.is)).toSeq,
      Full(ga.musicianGroup.instrument_id.toString), iid => {
        AppSchema.musicianGroups.update(mg => where(mg.id === ga.musicianGroup.id)
          set (mg.instrument_id := iid.toInt))
        Noop
      })

  def groupAssignments = {
    def delete() = {
      if (! selectedMusicianGroups.isEmpty) {
        Confirm(
          s"Are you sure you want to remove the ${selectedMusicianGroups.size} selected group assignments?",
          SHtml.ajaxInvoke(() => {
            AppSchema.musicianGroups.deleteWhere(_.id in selectedMusicianGroups.keys)
            selectedMusicianGroups = selectedMusicianGroups.empty
            Reload
          }))
      } else Noop
    }
    def create() = {
      for {
        group      <- Cache.groups.find(_.doesTesting)
        instrument <- Cache.instruments.find(_.name.is == "Unassigned")
        musician   <- opMusician
      } {
        AppSchema.musicianGroups.insert(MusicianGroup(0, musician.id, group.id, instrument.id,
          Terms.currentTerm))
      }
      Reload
    }

    "#delete" #> SHtml.ajaxButton("Delete selected group assignments", () => delete()) &
    "#create" #> SHtml.ajaxButton(s"Create ${Terms.formatted(Terms.currentTerm)} group assignment", () => create())
  }

  def assessments = ".assessmentRow" #> {
    val rows = opMusician.map(_.id).map(AssessmentRows.forMusician) | Seq[AssessmentRow]()
    rows.map(fillAssRow)
  }

  def newAssessment = {
    val lastPassFinder = new LastPassFinder()

    case class Pi(piece: Piece, instId: Int, opSubInstId: Option[Int])

    val opNextPi = for {
      musician  <- opMusician
      lastPass  <- lastPassFinder.lastPassed(Some(musician.musician_id.is)).headOption
      piece     <- Cache.pieces.find(_.id == lastPass.pieceId)
      nextPiece =  lastPassFinder.next(piece)
    } yield Pi(nextPiece, lastPass.instrumentId, lastPass.opSubinstrumentId)

    val sels = scala.collection.mutable.Map(Cache.tags.map(_.id -> false): _*)
    var opSelInstId = opNextPi.map(_.instId)
    var opSelSubinstId = opNextPi.flatMap(_.opSubInstId)
    var opSelPieceId = opNextPi.map(_.piece.id)
    var notes = ""

    def findTempo = opSelPieceId.flatMap(selPieceId =>
        // First look for a tempo for the specific instrument
        Cache.tempos.find(t => t.instrumentId == opSelInstId && t.pieceId == selPieceId) orElse
        Cache.tempos.find(_.pieceId == selPieceId))

    def recordAss(pass: Boolean): Unit = {
      for {
        musician  <- opMusician
        iid       <- opSelInstId
        pid       <- opSelPieceId
        user      <- AppSchema.users.find(_.login == Authenticator.userName.is)
      } {
        val newAss = Assessment(
          id                = 0,
          assessment_time   = new Timestamp(DateTime.now.getMillis),
          musician_id       = musician.musician_id.is,
          instrument_id     = iid,
          subinstrument_id  = opSelSubinstId,
          user_id           = user.id,
          pieceId           = pid,
          pass              = pass,
          notes             = notes
        )
        AppSchema.assessments.insert(newAss)
        AppSchema.assessmentTags.insert(
          for {
            (commentId, selected) <- sels
            if selected
          } yield AssessmentTag(newAss.id, commentId)
        )
      }
    }

    "#instrument" #> SHtml.ajaxSelect(Cache.instruments.map(p => p.id.toString -> p.name.is),
      opSelInstId.map(i => Full(i.toString)) getOrElse Empty, (p) => {
        opSelInstId = Some(p.toInt)
        Noop
    }) &
    "#subinstrument" #> SHtml.ajaxSelect(Seq[(String, String)](), Empty, (p) => {
      opSelSubinstId = Some(p.toInt)
      Noop
    }) &
    "#piece"      #> SHtml.ajaxSelect(Cache.pieces.map(p => p.id.toString -> p.name.is),
      opSelPieceId.map(p => Full(p.id.toString)) getOrElse Empty,
      (p) => {
        opSelPieceId = Some(p.toInt)
        SetHtml("tempo", Text(~findTempo.map(_.tempo.toString)))
      }) &
    "#tempo *"    #> ~findTempo.map(_.tempo.toString) &
    "#checkbox *" #> Cache.tags.map(tag =>
      <span>{SHtml.checkbox(false, (checked: Boolean) => sels(tag.id) = checked)} {tag.commentText}</span>) &
    "#commentText" #> SHtml.textarea("", (s: String) => {notes = s; Noop},
      "id" -> "commentText", "rows" -> {sels.values.size.toString}) &
    "#passButton" #> SHtml.ajaxSubmit("Pass", () => { recordAss(true); Noop }) &
    "#failButton" #> SHtml.ajaxSubmit("Fail", () => { recordAss(false); Noop })
  }

  private val dtf = DateTimeFormat.forStyle("S-")
  private val tmf = DateTimeFormat.forStyle("-M")

  private def fillAssRow(ar: AssessmentRow) =
    ".date       *"   #> <span title={tmf.print(ar.date)}>{dtf.print(ar.date)}</span> &
    ".tester     *"   #> ar.tester &
    ".piece [class]"  #> (if (ar.pass) "pass" else "fail") &
    ".piece      *"   #> ar.piece &
    ".instrument *"   #> ar.instrument &
    ".comments   *"   #> ar.notes
}
