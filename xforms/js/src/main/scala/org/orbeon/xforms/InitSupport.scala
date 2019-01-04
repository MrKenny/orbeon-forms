/**
  * Copyright (C) 2019 Orbeon, Inc.
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the
  * GNU Lesser General Public License as published by the Free Software Foundation; either version
  *  2.1 of the License, or (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
  * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
  * See the GNU Lesser General Public License for more details.
  *
  * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
  */
package org.orbeon.xforms

import io.circe.generic.auto._
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.oxf.util.StringUtils._
import org.orbeon.xforms.StateHandling.ClientState
import org.orbeon.xforms.facade.{AjaxServer, Globals, Properties}
import org.scalajs.dom
import org.scalajs.dom.ext._
import org.scalajs.dom.html

import scala.collection.{mutable ⇒ m}
import scala.scalajs.js
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.JSStringOps._
import scala.scalajs.js.annotation.{JSExportAll, JSExportTopLevel}

// Move to `Init` once `ORBEON.xforms.Init` is moved entirely to Scala.js
@JSExportTopLevel("ORBEON.xforms.InitSupport")
@JSExportAll
object InitSupport {

  def initialize(formElement: html.Form): Unit = {

    val formId = formElement.id

    formElement.elements.iterator collect { case e: html.Input ⇒ e } foreach {
      case e if e.name == "$uuid"           ⇒ Globals.formUUID        (formId) = e
      case e if e.name == "$server-events"  ⇒ Globals.formServerEvents(formId) = e
      case e if e.name == "$repeat-tree"    ⇒ processRepeatHierarchy(e.value)
      case e if e.name == "$repeat-indexes" ⇒ processRepeatIndexes(e.value)
      case _ ⇒ // NOP
    }

    StateHandling.findClientState(formId) match {
      case None ⇒
        // Initial state
        StateHandling.updateClientState(
          formId,
          ClientState(
            uuid     = Globals.formUUID(formId).value,
            sequence = 1
          )
        )
      case Some(_) if Properties.revisitHandling.get() == "reload" ⇒
        // The user reloaded or navigated back to this page and we must reload the page

        StateHandling.clearClientState(formId)
        Globals.isReloading = true
        dom.window.location.reload(flag = true)
        // NOTE: You would think that if reload is canceled, you would reset this to false, but
        // somehow this fails with IE.
      case Some(state) ⇒
        // The user reloaded or navigated back to this page and we must restore state

        // Old comment: Reset the value of the `$uuid` field to the value found in the client state,
        // because the browser sometimes restores the value of hidden fields in an erratic way, for
        // example from the value the hidden field had from the same URL loaded in another tab (e.g.
        // Chrome, Firefox).
        Globals.formUUID(formId).value = state.uuid

        AjaxServer.fireEvents(
          events      = js.Array(new AjaxServer.Event(formElement, null, null, "xxforms-all-events-required")),
          incremental = false
        )
    }
  }

  def parseRepeatIndexes(repeatIndexesString: String): List[(String, String)] =
    for {
      repeatIndexes ← repeatIndexesString.splitTo[List](",")
      repeatInfos   = repeatIndexes.splitTo[List]() // must be of the form "a b"
    } yield
      repeatInfos.head → repeatInfos.last

  def parseRepeatTree(repeatTreeString: String): List[(String, String)] =
    for {
     repeatTree  ← repeatTreeString.splitTo[List](",")
     repeatInfos = repeatTree.splitTo[List]() // must be of the form "a b"
     if repeatInfos.size > 1
   } yield
     repeatInfos.head → repeatInfos.last

  private def createParentToChildrenMap(childToParentMap: Map[String, String]): collection.Map[String, js.Array[String]] = {

    val parentToChildren = m.Map[String, js.Array[String]]()

     childToParentMap foreach { case (child, parent) ⇒
       Iterator.iterateOpt(parent)(childToParentMap.get) foreach { p ⇒
         parentToChildren.getOrElseUpdate(p, js.Array[String]()).push(child)
       }
     }

    parentToChildren
  }

  private def processRepeatIndexes(repeatIndexesString: String): Unit = {
    Globals.repeatIndexes = parseRepeatIndexes(repeatIndexesString).toMap.toJSDictionary
  }

  // NOTE: Also called from AjaxServer.js
  def processRepeatHierarchy(repeatTreeString: String): Unit = {

    val childToParent    = parseRepeatTree(repeatTreeString)
    val childToParentMap = childToParent.toMap

    Globals.repeatTreeChildToParent = childToParentMap.toJSDictionary

    val parentToChildren = m.Map[String, js.Array[String]]()

    childToParentMap foreach { case (child, parent) ⇒
      Iterator.iterateOpt(parent)(childToParentMap.get) foreach { p ⇒
        parentToChildren.getOrElseUpdate(p, js.Array[String]()).push(child)
      }
    }

    Globals.repeatTreeParentToAllChildren = createParentToChildrenMap(childToParentMap).toJSDictionary
  }
}