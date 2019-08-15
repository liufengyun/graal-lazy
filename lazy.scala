import sun.misc.Unsafe._

object LazyRuntime {
  val Evaluating = new LazyControl()

  private val unsafe: sun.misc.Unsafe = {
    val f: java.lang.reflect.Field = classOf[sun.misc.Unsafe].getDeclaredField("theUnsafe");
    f.setAccessible(true)
    f.get(null).asInstanceOf[sun.misc.Unsafe]
  }

  def fieldOffset(cls: Class[_], name: String): Long = {
    val fld = cls.getDeclaredField(name)
    fld.setAccessible(true)
    unsafe.objectFieldOffset(fld)
  }

  def isUnitialized(base: Object, offset: Long): Boolean =
    unsafe.compareAndSwapObject(base, offset, null, Evaluating)

  def initialize(base: Object, offset: Long, result: Object): Unit =
    if (!unsafe.compareAndSwapObject(base, offset, Evaluating, result)) {
      val lock = unsafe.getObject(base, offset).asInstanceOf[Waiting]
      unsafe.compareAndSwapObject(base, offset, lock, result)
      lock.release()
    }

  def awaitInitialized(base: Object, offset: Long, current: Object): Unit =
    if (current.isInstanceOf[Waiting])
      current.asInstanceOf[Waiting].awaitRelease()
    else
      unsafe.compareAndSwapObject(base, offset, Evaluating, new Waiting)
}

object C {
  import LazyRuntime.fieldOffset
  val x_offset = fieldOffset(classOf[C], "_x")
}

class LazyControl

class Waiting extends LazyControl {

  private var done = false

  def release(): Unit = synchronized {
    done = true
    notifyAll()
  }

  def awaitRelease(): Unit = synchronized {
    while (!done) wait()
  }
}

class C {
  @volatile private[this] var _x: AnyRef = _

  def x: String = {
    val current = _x
    if (current.isInstanceOf[String])
      current.asInstanceOf[String]
    else
      x$lzy
  }

  def x$lzy: String = {
    val current = _x
    if (current.isInstanceOf[String])
      current.asInstanceOf[String]
    else {
      val offset = C.x_offset
      if (current == null) {
        if (LazyRuntime.isUnitialized(this, offset)) {
          try LazyRuntime.initialize(this, offset, 3 + "value")
          catch {
            case ex: Throwable =>
              LazyRuntime.initialize(this, offset, null)
              throw ex
          }
        }
      }
      else
        LazyRuntime.awaitInitialized(this, offset, current)
      x$lzy
    }
  }
}

object Test {
  def loop(): Long = {
    val c = new C
    var i = 0
    var sum = 0L
    while (i < 10) {
      sum += c.x.length
      i += 1
    }
    sum
  }

  def main(args: Array[String]): Unit = {
    while(true) if (loop() == 0) sys.exit()
  }
}
