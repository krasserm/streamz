package streamz.akka.stream

import scala.collection.mutable
import scala.reflect._

import akka.actor.{ActorRef, Props, ActorRefFactory}


class MockActorRefFactory(overwriteProps: Map[Class[_], Props])(implicit realActorFactory: ActorRefFactory) extends ActorRefFactory {

  val createdActors: mutable.Map[Class[_], ActorRef] = mutable.Map.empty

  override protected def systemImpl = ???
  override def stop(actor: ActorRef) = ???
  override protected def lookupRoot = ???
  override protected def provider = ???
  override protected def guardian = ???
  override def actorOf(props: Props, name: String) = ???

  override implicit def dispatcher = realActorFactory.dispatcher

  override def actorOf(props: Props) = {
    val actor = realActorFactory.actorOf(overwriteProps.getOrElse(props.actorClass(), props))
    createdActors += (props.actorClass() -> actor)
    actor
  }

  def createdActor[A : ClassTag]: ActorRef = createdActors(classTag[A].runtimeClass)
}
