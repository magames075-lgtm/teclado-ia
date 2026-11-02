package com.magames.tecladoia.spell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class SymSpellTest {
    companion object {
        lateinit var sym: SymSpell

        @BeforeClass @JvmStatic
        fun load() {
            val f = listOf("src/main/assets/pt_br_50k.txt", "app/src/main/assets/pt_br_50k.txt")
                .map { File(it) }.first { it.exists() }
            sym = SymSpell()
            val t0 = System.currentTimeMillis()
            f.forEachLine { line ->
                val sp = line.lastIndexOf(' ')
                if (sp > 0) {
                    val w = line.substring(0, sp).lowercase()
                    val c = line.substring(sp + 1).toLongOrNull()
                    if (c != null && w.all { it.isLetter() || it == '-' }) sym.addWord(w, c)
                }
            }
            sym.build()
            println("built ${sym.size} words in ${System.currentTimeMillis() - t0} ms")
        }
    }

    private fun top(w: String) = sym.lookup(w, 3).map { it.term }.also { println("$w -> $it") }

    @Test fun knownWords() {
        assertTrue(sym.contains("você"))
        assertTrue(sym.contains("também"))
    }

    @Test fun typos() {
        assertEquals("obrigado", top("obrigdo").first())
        assertEquals("amanhã", top("amanha").first())
        assertEquals("porque", top("porqeu").first())
        assertEquals("trabalho", top("trabalo").first())
        assertTrue(top("cachora").contains("cachorra"))
        assertEquals("exceção", top("excecao").first())
        assertEquals("amanhã", sym.accentVariant("amanha"))
    }

    @Test fun accentVariants() {
        assertEquals("não", sym.accentVariant("nao"))
        assertEquals("você", sym.accentVariant("voce"))
        assertEquals(null, sym.accentVariant("esta"))
    }

    @Test fun completion() {
        println("compl obrig -> " + sym.complete("obrig"))
        assertTrue(sym.complete("obrig").isNotEmpty())
    }

    @Test fun speed() {
        val t0 = System.nanoTime()
        repeat(1000) { sym.lookup("trabalo") }
        val ms = (System.nanoTime() - t0) / 1_000_000.0 / 1000
        println("lookup avg $ms ms")
        assertTrue(ms < 20)
    }
}
