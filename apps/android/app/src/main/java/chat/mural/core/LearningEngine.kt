package chat.mural.core

data class WordState(
    val id:String,val lemma:String,val meaning:String,val form:String,val example:String,val bars:Int,
    val understandingCount:Int,val independentCount:Int,val lastSeen:Double,val dueAt:Double
) {
    val label get() = listOf("New","Fragile","Growing","Steady")[bars.coerceIn(0,3)]
    val explanation get() = when { independentCount==0 -> "Heard or used with support. Try using it in your own words."
        bars==1 -> "Used independently. We’ll bring it back soon."
        bars==2 -> "Recalled on different days. Still worth revisiting."
        else -> "Recalled across days and contexts. Strength can fade with time." }
}
data class LearnerState(val challenge:Int,val observationCount:Int,val nextGoal:String,val capabilities:List<String>,val words:List<WordState>) {
    val levelLabel get() = if (observationCount < 4) "Getting to know you" else "Finding your pace"
}

object LearningEngine {
    fun validate(proposal:Assessment, session:SessionRecord):Assessment? {
        if (LanguageRegistry.get(session.languageID)==null) return null
        val passage=session.passages.firstOrNull { it.id==proposal.passageID && it.speaker==Speaker.user } ?: return null
        if (passage.revisionKey != proposal.revisionKey || proposal.suggestedLevel !in 0..5 || proposal.words.size>12) return null
        val allowed=passage.fragments.map { it.id }.toSet()
        val join: (List<String>) -> String = if (proposal.textAssemblyVersion == null) Passage::legacyJoin else Passage::join
        val evidenceText = join(passage.fragments.map { it.text })
        val words=proposal.words.mapNotNull { word ->
            if (word.language != session.languageID || word.sourceIDs.isEmpty() || !allowed.containsAll(word.sourceIDs) ||
                !word.confidence.isFinite() || word.confidence !in 0.8..1.0 || word.lemma.isEmpty() || word.lemma.length>=100 ||
                word.meaning.isEmpty() || word.meaning.length>=180 || word.form.isEmpty() || word.quote.isEmpty() ||
                !evidenceText.containsCanonical(word.quote) || !word.quote.containsCanonical(word.form)) return@mapNotNull null
            val refs=join(passage.fragments.filter { word.sourceIDs.contains(it.id) }.map { it.text })
            if (!refs.containsCanonical(word.quote)) return@mapNotNull null
            var out=word
            if (out.kind==EvidenceKind.independent) {
                val modeled=session.passages.any { p -> p.speaker==Speaker.assistant && p.startMS<=passage.startMS && passage.startMS-p.endMS<90000 && join(p.fragments.map { it.text }).containsCanonical(word.form) }
                if (passage.fragments.any { it.meaningVisible || it.typed } || modeled) out=out.copy(kind=EvidenceKind.assisted)
            }
            out
        }
        return proposal.copy(nextGoal=proposal.nextGoal.take(300),capability=proposal.capability.take(160),words=words)
    }
    fun project(sessions:List<SessionRecord>,languageID:String=LanguageRegistry.defaultID,hiddenWords:List<String> = emptyList(),now:Double=nowSeconds()):LearnerState {
        val hidden=hiddenWords.map { it.canonical() }
        var level=0; var count=0; var successes=0
        var nextGoal="Start with a greeting and one small question. Adjust from what the learner actually says."
        val caps=mutableMapOf<String,MutableSet<String>>()
        val events=mutableMapOf<String,MutableList<Triple<WordProposal,Double,String>>>()
        for (session in sessions.filter { it.languageID==languageID }.sortedBy { it.startedAt }) {
            val seen=mutableSetOf<String>()
            for (raw in session.assessments.sortedBy { it.createdAt }) {
                if (raw.passageID in seen) continue
                val a=validate(raw,session) ?: continue
                seen.add(raw.passageID)
                count++
                when(a.outcome) {
                    Outcome.breakdown -> { level=(level-1).coerceAtLeast(0); successes=0 }
                    Outcome.success -> { successes++; if(successes>=2) { level=minOf(5, maxOf(level, minOf(level+1, a.suggestedLevel))); successes=0 } }
                    else -> successes=0
                }
                if(a.nextGoal.isNotEmpty()) nextGoal=a.nextGoal
                if(a.outcome==Outcome.success && a.capability.isNotEmpty()) caps.getOrPut(a.capability){mutableSetOf()}.add("${dayKey(a.createdAt)}|${a.context}")
                val seenWords=mutableSetOf<String>()
                for(word in a.words) if(hidden.none { it==word.key } && seenWords.add(word.key)) events.getOrPut(word.key){mutableListOf()}.add(Triple(word,a.createdAt,a.context))
            }
        }
        val words=events.mapNotNull { (key,obs) ->
            val last=obs.lastOrNull() ?: return@mapNotNull null
            val independent=obs.filter { it.first.kind==EvidenceKind.independent }
            val days=independent.map { dayKey(it.second) }.toSet().size
            val contexts=independent.map { it.third }.toSet().size
            val lastRecall=independent.lastOrNull()?.second
            var bars=if(independent.isEmpty()) 0 else 1
            if(days>=2) bars=2
            if(days>=3 && contexts>=2 && independent.last().second-independent.first().second>=7*86400) bars=3
            val due=(lastRecall ?: last.second)+listOf(1.0,1.0,4.0,14.0)[bars]*86400
            if(now>due && bars>1) bars--
            val lapse=obs.lastOrNull { it.first.kind==EvidenceKind.lapse }
            if(lapse!=null && lapse.second>(lastRecall ?: Double.NEGATIVE_INFINITY)) bars=minOf(bars,1)
            WordState(key,last.first.lemma,last.first.meaning,last.first.form,last.first.quote,bars,
                obs.count { it.first.kind==EvidenceKind.understanding },independent.size,last.second,due)
        }.sortedByDescending { it.lastSeen }
        return LearnerState(level,count,nextGoal,caps.filterValues { it.size>=3 }.keys.sorted(),words)
    }
    private fun dayKey(seconds:Double):String = java.time.Instant.ofEpochSecond((seconds + APPLE_EPOCH_UNIX_SECONDS).toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
}
