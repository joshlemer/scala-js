/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js Test Suite        **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013, LAMP/EPFL        **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */
package org.scalajs.testsuite.niocharset

import scala.collection.JavaConverters._

import java.nio.charset._

import org.junit.Test
import org.junit.Assert._

import org.scalajs.testsuite.utils.AssertThrows._
import org.scalajs.testsuite.utils.Platform.executingInJVM

class CharsetTest {
  def javaSet[A](elems: A*): java.util.Set[A] = Set(elems: _*).asJava

  @Test def defaultCharset(): Unit = {
    assertSame("UTF-8", Charset.defaultCharset().name())
  }

  @Test def forName(): Unit = {
    assertEquals("ISO-8859-1", Charset.forName("ISO-8859-1").name())
    assertEquals("ISO-8859-1", Charset.forName("Iso8859-1").name())
    assertEquals("ISO-8859-1", Charset.forName("iso_8859_1").name())
    assertEquals("ISO-8859-1", Charset.forName("LaTin1").name())
    assertEquals("ISO-8859-1", Charset.forName("l1").name())

    assertEquals("US-ASCII", Charset.forName("US-ASCII").name())
    assertEquals("US-ASCII", Charset.forName("Default").name())

    assertEquals("UTF-8", Charset.forName("UTF-8").name())
    assertEquals("UTF-8", Charset.forName("utf-8").name())
    assertEquals("UTF-8", Charset.forName("UtF8").name())

    assertEquals("UTF-16BE", Charset.forName("UTF-16BE").name())
    assertEquals("UTF-16BE", Charset.forName("Utf_16BE").name())
    assertEquals("UTF-16BE", Charset.forName("UnicodeBigUnmarked").name())

    assertEquals("UTF-16LE", Charset.forName("UTF-16le").name())
    assertEquals("UTF-16LE", Charset.forName("Utf_16le").name())
    assertEquals("UTF-16LE", Charset.forName("UnicodeLittleUnmarked").name())

    assertEquals("UTF-16", Charset.forName("UTF-16").name())
    assertEquals("UTF-16", Charset.forName("Utf_16").name())
    assertEquals("UTF-16", Charset.forName("unicode").name())
    assertEquals("UTF-16", Charset.forName("UnicodeBig").name())

    // Issue #2040
    expectThrows(classOf[UnsupportedCharsetException], Charset.forName("UTF_8"))

    expectThrows(classOf[UnsupportedCharsetException],
        Charset.forName("this-charset-does-not-exist"))
  }

  @Test def isSupported(): Unit = {
    assertTrue(Charset.isSupported("ISO-8859-1"))
    assertTrue(Charset.isSupported("US-ASCII"))
    assertTrue(Charset.isSupported("Default"))
    assertTrue(Charset.isSupported("utf-8"))
    assertTrue(Charset.isSupported("UnicodeBigUnmarked"))
    assertTrue(Charset.isSupported("Utf_16le"))
    assertTrue(Charset.isSupported("UTF-16"))
    assertTrue(Charset.isSupported("unicode"))

    assertFalse(Charset.isSupported("this-charset-does-not-exist"))
  }

  @Test def aliases(): Unit = {
    assertEquals(Charset.forName("UTF-8").aliases(),
        javaSet("UTF8", "unicode-1-1-utf-8"))
    assertEquals(Charset.forName("UTF-16").aliases(),
        javaSet("UTF_16", "unicode", "utf16", "UnicodeBig"))
    assertEquals(Charset.forName("UTF-16BE").aliases(),
        javaSet("X-UTF-16BE", "UTF_16BE", "ISO-10646-UCS-2",
            "UnicodeBigUnmarked"))
    assertEquals(Charset.forName("UTF-16LE").aliases(),
        javaSet("UnicodeLittleUnmarked", "UTF_16LE", "X-UTF-16LE"))
    assertEquals(Charset.forName("US-ASCII").aliases(),
        javaSet("ANSI_X3.4-1968", "cp367", "csASCII", "iso-ir-6", "ASCII",
            "iso_646.irv:1983", "ANSI_X3.4-1986", "ascii7", "default",
            "ISO_646.irv:1991", "ISO646-US", "IBM367", "646", "us"))
    assertEquals(Charset.forName("ISO-8859-1").aliases(),
        javaSet("819", "ISO8859-1", "l1", "ISO_8859-1:1987", "ISO_8859-1", "8859_1",
            "iso-ir-100", "latin1", "cp819", "ISO8859_1", "IBM819", "ISO_8859_1",
            "IBM-819", "csISOLatin1"))
  }
}
