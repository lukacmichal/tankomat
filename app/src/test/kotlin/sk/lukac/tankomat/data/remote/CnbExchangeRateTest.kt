package sk.lukac.tankomat.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class CnbExchangeRateTest {

    private val sample = """
        04.05.2026 #84
        země|měna|množství|kód|kurz
        Austrálie|dolar|1|AUD|14,830
        EMU|euro|1|EUR|24,395
        USA|dolar|1|USD|22,650
    """.trimIndent()

    @Test
    fun `parses EUR row from CNB pipe-delimited file`() {
        val czkPerEur = CnbExchangeRate.parseCzkPerEur(sample)
        assertEquals(24.395, czkPerEur, 0.0001)
    }

    @Test
    fun `falls back when EUR row missing`() {
        val noEur = sample.lines().filterNot { it.contains("EUR") }.joinToString("\n")
        val czkPerEur = CnbExchangeRate.parseCzkPerEur(noEur)
        // fallback hodnota — zostaňme nad 20, pod 30
        assert(czkPerEur in 20.0..30.0) { "fallback rate $czkPerEur out of range" }
    }
}
