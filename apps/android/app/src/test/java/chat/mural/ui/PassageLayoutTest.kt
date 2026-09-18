package chat.mural.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PassageLayoutTest {
    private fun split(available: Float, target: Float = 140f, meaning: Float = 75f) =
        passageHeights(available, target, targetCap = 200f, targetMin = 34f, meaning = meaning, meaningPeek = 50f)

    @Test fun aPassageThatFitsKeepsItsFullHeightAndSoDoesTheMeaning() {
        assertEquals(PassageHeights(140f, 75f), split(available = 400f))
    }

    @Test fun aVeryLongPassageStopsAtTheCapEvenWithRoomToSpare() {
        assertEquals(PassageHeights(200f, 75f), split(available = 400f, target = 260f))
    }

    @Test fun tightSpaceLeavesTheMeaningItsFirstLines() {
        assertEquals(PassageHeights(120f, 50f), split(available = 170f))
    }

    @Test fun aShortMeaningOnlyReservesWhatItNeeds() {
        assertEquals(PassageHeights(130f, 20f), split(available = 150f, meaning = 20f))
    }

    @Test fun theTargetAlwaysKeepsOneLineBeforeTheMeaningGetsItsPeek() {
        assertEquals(PassageHeights(34f, 26f), split(available = 60f))
    }

    @Test fun whenNotEvenOneLineFitsTheTargetTakesWhatThereIs() {
        assertEquals(PassageHeights(30f, 0f), split(available = 30f))
        assertEquals(PassageHeights(0f, 0f), split(available = -12f))
    }

    @Test fun aHiddenMeaningReservesNothing() {
        assertEquals(PassageHeights(140f, 0f), passageHeights(150f, 140f, targetCap = 200f, targetMin = 34f, meaning = 0f, meaningPeek = 0f))
    }
}
