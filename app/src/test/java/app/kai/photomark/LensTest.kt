package app.kai.photomark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensTest {
    private fun lens(name: String?, make: String? = null, spec: String? = null, camera: String = "SONY", model: String = "ILCE-7M4") =
        Lenses.display(name, make, spec, camera, model)

    @Test fun firstPartyNamesStayAsTheMakerWritesThem() {
        assertEquals("NIKKOR Z 24-70mm f/2.8 S", lens("NIKKOR Z 24-70mm f/2.8 S", camera = "NIKON CORPORATION", model = "NIKON Z 8"))
        assertEquals("FE 24-70mm F2.8 GM II", lens("FE 24-70mm F2.8 GM II"))
        assertEquals("RF24-70mm F2.8 L IS USM", lens("RF24-70mm F2.8 L IS USM", camera = "Canon", model = "Canon EOS R5"))
        assertEquals("XF23mmF1.4 R LM WR", lens("XF23mmF1.4 R LM WR", camera = "FUJIFILM", model = "X-T5"))
        assertEquals("M.Zuiko Digital ED 12-40mm F2.8 PRO", lens("M.Zuiko Digital ED 12-40mm F2.8 PRO  "))
    }

    @Test fun thirdPartyLensesGetTheirMaker() {
        assertEquals("SIGMA 35mm F1.4 DG DN | Art", lens("35mm F1.4 DG DN | Art 019"))
        assertEquals("SIGMA 24-70mm F2.8 DG DN II | Art", lens("24-70mm F2.8 DG DN II | Art 024", make = "SIGMA"))
        assertEquals("TAMRON 28-75mm F/2.8 Di III VXD G2", lens("28-75mm F/2.8 Di III VXD G2 (A063)"))
        assertEquals("TAMRON 28-75mm F2.8 Di III VXD G2", lens("TAMRON 28-75mm F2.8 Di III VXD G2", make = "TAMRON"))
        assertEquals("Viltrox AF 56/1.4 XF", lens("AF 56/1.4 XF", camera = "FUJIFILM", model = "X-T5"))
        assertEquals("ZEISS Batis 2/25", lens("Batis 2/25", make = "ZEISS"))
    }

    @Test fun placeholdersGiveNothingSoAManualLensCanBeTypedIn() {
        for (placeholder in listOf("----", "", "  ", "0.0 mm f/0.0", "E 0mm F0", "Unknown", "N/A", "0")) {
            assertNull(placeholder, lens(placeholder))
        }
        assertNull(lens(null))
    }

    @Test fun lensSpecificationFillsInWhenTheNameIsMissing() {
        assertEquals("24-70mm f/2.8", lens(null, spec = "24/1,70/1,28/10,28/10", camera = "NIKON CORPORATION", model = "NIKON D850"))
        assertEquals("24-105mm F3.5-5.6", lens(null, spec = "24/1,105/1,35/10,56/10"))
        assertEquals("50mm F1.8", lens("----", spec = "50/1,50/1,18/10,18/10"))
        assertEquals("SIGMA 105mm F1.4", lens(null, make = "SIGMA", spec = "105/1,105/1,14/10,14/10"))
        assertEquals("16mm", lens(null, spec = "16/1,16/1,0/0,0/0"))
        assertNull(lens(null, spec = "0/0,0/0,0/0,0/0"))
    }

    @Test fun phoneLensesReadInChinese() {
        assertEquals("后置三摄 6.765mm f/1.78", lens("iPhone 15 Pro back triple camera 6.765mm f/1.78", camera = "Apple", model = "iPhone 15 Pro"))
        assertEquals("前置镜头 2.69mm f/1.9", lens("iPhone 15 Pro front camera 2.69mm f/1.9", camera = "Apple", model = "iPhone 15 Pro"))
        assertNull(lens(null, spec = "1.54/1,6.765/1,178/100,28/10", camera = "Apple", model = "iPhone 15 Pro"))
    }
}
