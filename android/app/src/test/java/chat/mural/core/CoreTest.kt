package chat.mural.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonObject

class CoreTest {
    private fun evidence(language:String="nb", day:Double=0.0, kind:EvidenceKind=EvidenceKind.independent, supported:Boolean=false, theme:String="walk"):SessionRecord {
        val date=1_780_000_000.0 + day*86400
        val s=SessionRecord(languageID=language,startedAt=date,themeID=theme)
        s.append(Fragment(id="f-${language}-${day}",speaker=Speaker.user,text="radio",startMS=1000,endMS=2000,receivedAt=date,meaningVisible=supported))
        val p=s.passages[0]
        s.assessments += Assessment(passageID=p.id,revisionKey=p.revisionKey,outcome=Outcome.success,suggestedLevel=2,nextGoal="A goal",capability="Uses a familiar word",words=listOf(WordProposal("radio","radio","radio",kind,.95,listOf("f-${language}-${day}"),"radio",language)),createdAt=date,context=theme)
        return s
    }
    @Test fun transcriptGroupingPreservesWhitespaceAndTypedBoundaries() {
        val a=Fragment(id="a",speaker=Speaker.assistant,text="Hva",startMS=0,endMS=100)
        val b=Fragment(id="b",speaker=Speaker.assistant,text=" gjorde du?",startMS=100,endMS=400)
        assertEquals("Hva gjorde du?",Transcript.passages(listOf(a,b))[0].text)
        val typed=Fragment(id="c",speaker=Speaker.assistant,text="typed",startMS=500,endMS=600,typed=true)
        assertEquals(2,Transcript.passages(listOf(a,b,typed)).size)
    }
    @Test fun supportedAndWrongLanguageEvidenceCannotBecomeIndependent() {
        val supported=evidence(supported=true)
        assertEquals(EvidenceKind.assisted,LearningEngine.validate(supported.assessments[0],supported)!!.words[0].kind)
        val wrong=evidence(); wrong.assessments[0].words= listOf(wrong.assessments[0].words[0].copy(language="es"))
        assertTrue(LearningEngine.validate(wrong.assessments[0],wrong)!!.words.isEmpty())
    }
    @Test fun progressionNeedsSpacedDifferentContextsAndIsLanguageScoped() {
        val first=evidence(day=0.0,theme="walk")
        val second=evidence(day=2.0,theme="walk")
        val third=evidence(day=8.0,theme="dinner")
        assertEquals(3,LearningEngine.project(listOf(first,second,third),now=third.startedAt).words[0].bars)
        assertEquals(0,LearningEngine.project(listOf(first,second,third),languageID="es").observationCount)
    }
    @Test fun archiveV1MigrationAndMergePreserveLocalPreferences() {
        val original=Archive(sessions= mutableListOf(evidence()),preferences=Preferences(hiddenWords=listOf("nb|radio"),meaningLanguage="Spanish"))
        val root=kotlinx.serialization.json.Json.parseToJsonElement(ArchiveCodec.encode(original)).jsonObject.toMutableMap()
        root["schemaVersion"]=kotlinx.serialization.json.JsonPrimitive(1)
        val prefs=root["preferences"]!!.jsonObject.toMutableMap(); prefs.remove("learningLanguageID")
        prefs["hiddenWords"]=kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("radio|radio")) }
        root["preferences"]=kotlinx.serialization.json.JsonObject(prefs)
        val migrated=ArchiveCodec.decode(kotlinx.serialization.json.JsonObject(root).toString())
        assertEquals("nb",migrated.preferences.learningLanguageID)
        assertEquals(listOf("nb|radio|radio"),migrated.preferences.hiddenWords)
        val incoming=Archive(sessions= mutableListOf(evidence(language="es")),preferences=Preferences(meaningLanguage="English"))
        val merged=ArchiveCodec.merge(original,incoming)
        assertEquals("Spanish",merged.preferences.meaningLanguage)
        assertEquals(2,merged.sessions.size)
    }
    @Test fun languageRegistryAndThemesStayStable() {
        assertEquals(listOf("nb","es","en","fr","de","it","pt","zh"),LanguageRegistry.all.map { it.id })
        assertEquals(24,Themes.shared.map { it.id }.toSet().size)
        assertEquals("Salut !",LanguageRegistry.get("fr")!!.greeting)
        for ((id, locale, greeting) in listOf(Triple("de","de-DE","Hallo!"),Triple("it","it-IT","Ciao!"),Triple("pt","pt-BR","Olá!"),Triple("zh","zh-CN","你好！"))) {
            assertEquals(locale,LanguageRegistry.get(id)!!.locale); assertEquals(greeting,LanguageRegistry.get(id)!!.greeting)
        }
        assertTrue(MeaningLanguages.all.contains("Chinese (Simplified)"))
        assertEquals("你好！",MeaningLanguages.greeting("Chinese (Simplified)"))
        assertEquals("Norwegian",LanguageRegistry.get("nb")!!.name)
        assertEquals("Hei!",MeaningLanguages.greeting("Norwegian"))
    }
}
