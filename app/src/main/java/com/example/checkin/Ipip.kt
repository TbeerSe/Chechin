package com.example.checkin

import kotlin.math.abs

/** IPIP-NEO-120 item. Domain/facet are derived from the item number (Johnson, 2014, Table 1). */
class IpipItem(val n: Int, val key: Int, val ru: String, val en: String) {
    val domain: Int get() = (n - 1) % 5
    val facetInDomain: Int get() = ((n - 1) % 30) / 5
    val facet: Int get() = domain * 6 + facetInDomain
}

class TestResult(
    val id: String,
    val instrument: String,
    val takenAt: Long,
    val answers: List<Int>,
    val activeSec: Int
)

class IpipDraft(val answers: List<Int>, val activeMs: Long, val startedAt: Long)

object Ipip {
    const val ID = "ipip-neo-120"
    const val COUNT = 120

    val DOMAIN_RU = listOf("Нейротизм", "Экстраверсия", "Открытость опыту", "Доброжелательность", "Добросовестность")
    val DOMAIN_EN = listOf("Neuroticism", "Extraversion", "Openness to Experience", "Agreeableness", "Conscientiousness")
    val DOMAIN_DESC = listOf(
        "Склонность к негативным переживаниям: тревоге, гневу, подавленности, а также чувствительность к стрессу.",
        "Общительность, активность, склонность к положительным эмоциям и поиску стимуляции.",
        "Интерес к новому, воображению, искусству, идеям и разным взглядам.",
        "Доверие к людям, забота о них, готовность к сотрудничеству и уступчивость.",
        "Организованность, обязательность, самодисциплина и стремление к достижениям."
    )

    val FACET_RU = listOf(
        "Тревожность", "Гнев", "Депрессивность", "Застенчивость", "Несдержанность", "Уязвимость",
        "Дружелюбие", "Общительность", "Напористость", "Уровень активности", "Поиск острых ощущений", "Жизнерадостность",
        "Воображение", "Художественные интересы", "Эмоциональность", "Тяга к новому", "Интеллектуальность", "Либерализм (ценности)",
        "Доверие", "Нравственность", "Альтруизм", "Уступчивость", "Скромность", "Сочувствие",
        "Самоэффективность", "Аккуратность", "Обязательность", "Стремление к достижениям", "Самодисциплина", "Осмотрительность"
    )
    val FACET_EN = listOf(
        "Anxiety", "Anger", "Depression", "Self-Consciousness", "Immoderation", "Vulnerability",
        "Friendliness", "Gregariousness", "Assertiveness", "Activity Level", "Excitement-Seeking", "Cheerfulness",
        "Imagination", "Artistic Interests", "Emotionality", "Adventurousness", "Intellect", "Liberalism",
        "Trust", "Morality", "Altruism", "Cooperation", "Modesty", "Sympathy",
        "Self-Efficacy", "Orderliness", "Dutifulness", "Achievement-Striving", "Self-Discipline", "Cautiousness"
    )

    /** Cronbach alpha, IPIP-NEO-120, Internet sample N = 619,150 (Johnson, 2014, Table 2). */
    val DOMAIN_ALPHA = listOf(0.90, 0.89, 0.81, 0.86, 0.90)
    val FACET_ALPHA = listOf(
        0.78, 0.87, 0.85, 0.70, 0.69, 0.76,
        0.81, 0.79, 0.85, 0.69, 0.73, 0.79,
        0.74, 0.74, 0.65, 0.70, 0.73, 0.63,
        0.85, 0.74, 0.73, 0.71, 0.73, 0.72,
        0.77, 0.83, 0.67, 0.79, 0.71, 0.88
    )

    val SCALE_LABELS = listOf(
        "Совсем не подходит",
        "Скорее не подходит",
        "Ни то ни другое",
        "Скорее подходит",
        "Полностью подходит"
    )
    val SCALE_LABELS_EN = listOf("Very Inaccurate", "Moderately Inaccurate", "Neither Accurate Nor Inaccurate", "Moderately Accurate", "Very Accurate")

    val ITEMS: List<IpipItem> = listOf(
        IpipItem(1, 1, "Переживаю по разным поводам", "Worry about things"),
        IpipItem(2, 1, "Легко завожу друзей", "Make friends easily"),
        IpipItem(3, 1, "У меня яркое воображение", "Have a vivid imagination"),
        IpipItem(4, 1, "Доверяю другим людям", "Trust others"),
        IpipItem(5, 1, "Успешно завершаю задачи", "Complete tasks successfully"),
        IpipItem(6, 1, "Легко прихожу в гнев", "Get angry easily"),
        IpipItem(7, 1, "Люблю многолюдные вечеринки", "Love large parties"),
        IpipItem(8, 1, "Считаю искусство важным", "Believe in the importance of art"),
        IpipItem(9, -1, "Использую других в своих целях", "Use others for my own ends"),
        IpipItem(10, 1, "Люблю наводить порядок", "Like to tidy up"),
        IpipItem(11, 1, "Мне часто грустно", "Often feel blue"),
        IpipItem(12, 1, "Беру на себя руководство", "Take charge"),
        IpipItem(13, 1, "Очень сильно переживаю свои эмоции", "Experience my emotions intensely"),
        IpipItem(14, 1, "Люблю помогать другим", "Love to help others"),
        IpipItem(15, 1, "Держу свои обещания", "Keep my promises"),
        IpipItem(16, 1, "Мне трудно первым подойти к другим людям", "Find it difficult to approach others"),
        IpipItem(17, 1, "Я постоянно в делах", "Am always busy"),
        IpipItem(18, 1, "Предпочитаю разнообразие рутине", "Prefer variety to routine"),
        IpipItem(19, -1, "Люблю хорошую перепалку", "Love a good fight"),
        IpipItem(20, 1, "Усердно работаю", "Work hard"),
        IpipItem(21, 1, "Время от времени ударяюсь в излишества", "Go on binges"),
        IpipItem(22, 1, "Люблю острые ощущения", "Love excitement"),
        IpipItem(23, 1, "Люблю читать сложные тексты", "Love to read challenging material"),
        IpipItem(24, -1, "Считаю себя лучше других", "Believe that I am better than others"),
        IpipItem(25, 1, "Всегда подготовлен(а)", "Am always prepared"),
        IpipItem(26, 1, "Легко впадаю в панику", "Panic easily"),
        IpipItem(27, 1, "Излучаю радость", "Radiate joy"),
        IpipItem(28, 1, "Как правило, голосую за либеральных политических кандидатов", "Tend to vote for liberal political candidates"),
        IpipItem(29, 1, "Сочувствую бездомным", "Sympathize with the homeless"),
        IpipItem(30, -1, "Берусь за дело, не подумав", "Jump into things without thinking"),
        IpipItem(31, 1, "Боюсь, что случится худшее", "Fear for the worst"),
        IpipItem(32, 1, "Чувствую себя комфортно среди людей", "Feel comfortable around people"),
        IpipItem(33, 1, "Люблю смелые полёты фантазии", "Enjoy wild flights of fantasy"),
        IpipItem(34, 1, "Верю, что у других людей добрые намерения", "Believe that others have good intentions"),
        IpipItem(35, 1, "Преуспеваю в том, чем занимаюсь", "Excel in what I do"),
        IpipItem(36, 1, "Легко раздражаюсь", "Get irritated easily"),
        IpipItem(37, 1, "На вечеринках разговариваю со множеством разных людей", "Talk to a lot of different people at parties"),
        IpipItem(38, 1, "Вижу красоту там, где другие её могут не заметить", "See beauty in things that others might not notice"),
        IpipItem(39, -1, "Жульничаю, чтобы продвинуться вперёд", "Cheat to get ahead"),
        IpipItem(40, -1, "Часто забываю вернуть вещи на их места", "Often forget to put things back in their proper place"),
        IpipItem(41, 1, "Я себе не нравлюсь", "Dislike myself"),
        IpipItem(42, 1, "Стараюсь вести за собой других", "Try to lead others"),
        IpipItem(43, 1, "Чувствую эмоции других людей", "Feel others' emotions"),
        IpipItem(44, 1, "Беспокоюсь о других людях", "Am concerned about others"),
        IpipItem(45, 1, "Говорю правду", "Tell the truth"),
        IpipItem(46, 1, "Боюсь привлекать к себе внимание", "Am afraid to draw attention to myself"),
        IpipItem(47, 1, "Я всегда в движении", "Am always on the go"),
        IpipItem(48, -1, "Предпочитаю держаться того, что мне знакомо", "Prefer to stick with things that I know"),
        IpipItem(49, -1, "Кричу на людей", "Yell at people"),
        IpipItem(50, 1, "Делаю больше, чем от меня ожидают", "Do more than what's expected of me"),
        IpipItem(51, -1, "Редко позволяю себе лишнее", "Rarely overindulge"),
        IpipItem(52, 1, "Ищу приключений", "Seek adventure"),
        IpipItem(53, -1, "Избегаю философских дискуссий", "Avoid philosophical discussions"),
        IpipItem(54, -1, "Высоко о себе думаю", "Think highly of myself"),
        IpipItem(55, 1, "Выполняю свои планы", "Carry out my plans"),
        IpipItem(56, 1, "Меня захлёстывают события", "Become overwhelmed by events"),
        IpipItem(57, 1, "Часто от души веселюсь", "Have a lot of fun"),
        IpipItem(58, 1, "Считаю, что не существует абсолютно правильного и абсолютно неправильного", "Believe that there is no absolute right or wrong"),
        IpipItem(59, 1, "Сочувствую тем, кому живётся хуже, чем мне", "Feel sympathy for those who are worse off than myself"),
        IpipItem(60, -1, "Принимаю опрометчивые решения", "Make rash decisions"),
        IpipItem(61, 1, "Многого боюсь", "Am afraid of many things"),
        IpipItem(62, -1, "Избегаю контактов с другими людьми", "Avoid contacts with others"),
        IpipItem(63, 1, "Люблю мечтать наяву", "Love to daydream"),
        IpipItem(64, 1, "Доверяю тому, что говорят люди", "Trust what people say"),
        IpipItem(65, 1, "Справляюсь с задачами без затруднений", "Handle tasks smoothly"),
        IpipItem(66, 1, "Выхожу из себя", "Lose my temper"),
        IpipItem(67, -1, "Предпочитаю оставаться в одиночестве", "Prefer to be alone"),
        IpipItem(68, -1, "Не люблю поэзию", "Do not like poetry"),
        IpipItem(69, -1, "Извлекаю выгоду за счёт других", "Take advantage of others"),
        IpipItem(70, -1, "Оставляю беспорядок в своей комнате", "Leave a mess in my room"),
        IpipItem(71, 1, "Часто бываю в унынии", "Am often down in the dumps"),
        IpipItem(72, 1, "Беру ситуацию под свой контроль", "Take control of things"),
        IpipItem(73, -1, "Редко замечаю свои эмоциональные реакции", "Rarely notice my emotional reactions"),
        IpipItem(74, -1, "Мне безразличны чувства других людей", "Am indifferent to the feelings of others"),
        IpipItem(75, -1, "Нарушаю правила", "Break rules"),
        IpipItem(76, 1, "Чувствую себя свободно только с друзьями", "Only feel comfortable with friends"),
        IpipItem(77, 1, "Многим занимаюсь в свободное время", "Do a lot in my spare time"),
        IpipItem(78, -1, "Не люблю перемен", "Dislike changes"),
        IpipItem(79, -1, "Оскорбляю людей", "Insult people"),
        IpipItem(80, -1, "Делаю ровно столько, сколько нужно, чтобы отделаться", "Do just enough work to get by"),
        IpipItem(81, -1, "Легко сопротивляюсь соблазнам", "Easily resist temptations"),
        IpipItem(82, 1, "Мне нравится вести себя безрассудно", "Enjoy being reckless"),
        IpipItem(83, -1, "Мне трудно понимать абстрактные идеи", "Have difficulty understanding abstract ideas"),
        IpipItem(84, -1, "Имею высокое мнение о себе", "Have a high opinion of myself"),
        IpipItem(85, -1, "Трачу время впустую", "Waste my time"),
        IpipItem(86, 1, "Мне кажется, что я не справляюсь с происходящим", "Feel that I'm unable to deal with things"),
        IpipItem(87, 1, "Люблю жизнь", "Love life"),
        IpipItem(88, -1, "Как правило, голосую за консервативных политических кандидатов", "Tend to vote for conservative political candidates"),
        IpipItem(89, -1, "Меня не интересуют проблемы других людей", "Am not interested in other people's problems"),
        IpipItem(90, -1, "Бросаюсь в дело поспешно", "Rush into things"),
        IpipItem(91, 1, "Легко впадаю в стресс", "Get stressed out easily"),
        IpipItem(92, -1, "Держу других на расстоянии", "Keep others at a distance"),
        IpipItem(93, 1, "Люблю погружаться в свои мысли", "Like to get lost in thought"),
        IpipItem(94, -1, "Не доверяю людям", "Distrust people"),
        IpipItem(95, 1, "Знаю, как довести дела до конца", "Know how to get things done"),
        IpipItem(96, -1, "Меня нелегко раздражить", "Am not easily annoyed"),
        IpipItem(97, -1, "Избегаю толпы", "Avoid crowds"),
        IpipItem(98, -1, "Не получаю удовольствия от посещения художественных музеев", "Do not enjoy going to art museums"),
        IpipItem(99, -1, "Мешаю осуществлению чужих планов", "Obstruct others' plans"),
        IpipItem(100, -1, "Разбрасываю свои вещи", "Leave my belongings around"),
        IpipItem(101, -1, "Мне комфортно с самим собой", "Feel comfortable with myself"),
        IpipItem(102, -1, "Жду, пока кто-то другой возьмёт на себя лидерство", "Wait for others to lead the way"),
        IpipItem(103, -1, "Не понимаю людей, которые бурно проявляют эмоции", "Don't understand people who get emotional"),
        IpipItem(104, -1, "Не нахожу времени для других", "Take no time for others"),
        IpipItem(105, -1, "Нарушаю свои обещания", "Break my promises"),
        IpipItem(106, -1, "Меня не смущают сложные ситуации в общении", "Am not bothered by difficult social situations"),
        IpipItem(107, -1, "Люблю не напрягаться", "Like to take it easy"),
        IpipItem(108, -1, "Придерживаюсь традиционных, общепринятых способов", "Am attached to conventional ways"),
        IpipItem(109, -1, "Мщу другим", "Get back at others"),
        IpipItem(110, -1, "Вкладываю в работу мало времени и сил", "Put little time and effort into my work"),
        IpipItem(111, -1, "Могу держать свои влечения под контролем", "Am able to control my cravings"),
        IpipItem(112, 1, "Позволяю себе безумные выходки", "Act wild and crazy"),
        IpipItem(113, -1, "Меня не интересуют теоретические дискуссии", "Am not interested in theoretical discussions"),
        IpipItem(114, -1, "Хвастаюсь своими достоинствами", "Boast about my virtues"),
        IpipItem(115, -1, "С трудом приступаю к делам", "Have difficulty starting tasks"),
        IpipItem(116, -1, "Сохраняю спокойствие под давлением", "Remain calm under pressure"),
        IpipItem(117, 1, "Смотрю на светлую сторону жизни", "Look at the bright side of life"),
        IpipItem(118, -1, "Считаю, что к преступности нужно относиться жёстко", "Believe that we should be tough on crime"),
        IpipItem(119, -1, "Стараюсь не думать о нуждающихся", "Try not to think about the needy"),
        IpipItem(120, -1, "Действую не думая", "Act without thinking")
    )

    /** 29 pairs of items with opposite keying (Johnson, 2014, Appendix A). */
    val PAIRS: List<Pair<Int, Int>> = listOf(4 to 94, 44 to 74, 59 to 119, 10 to 70, 15 to 105, 45 to 75, 20 to 110, 50 to 80, 25 to 115, 55 to 85, 2 to 62, 32 to 92, 7 to 97, 37 to 67, 12 to 102, 77 to 107, 36 to 96, 41 to 101, 16 to 106, 21 to 51, 56 to 116, 8 to 98, 38 to 68, 13 to 73, 43 to 103, 18 to 48, 23 to 113, 28 to 88, 58 to 118)

    private val byFacet: List<List<IpipItem>> = (0 until 30).map { g -> ITEMS.filter { it.facet == g } }
    private val byDomain: List<List<IpipItem>> = (0 until 5).map { d -> ITEMS.filter { it.domain == d } }

    private fun value(it: IpipItem, answers: List<Int>): Int {
        val a = answers[it.n - 1]
        return if (it.key > 0) a else 6 - a
    }

    fun facetMean(g: Int, answers: List<Int>): Double = byFacet[g].map { value(it, answers) }.average()
    fun facetSum(g: Int, answers: List<Int>): Int = byFacet[g].sumOf { value(it, answers) }
    fun domainMean(d: Int, answers: List<Int>): Double = byDomain[d].map { value(it, answers) }.average()
    fun domainSum(d: Int, answers: List<Int>): Int = byDomain[d].sumOf { value(it, answers) }

    /** Mean |a + b - 6| over opposite-keyed pairs. Independent random answers give about 1.6. */
    fun inconsistency(answers: List<Int>): Double =
        PAIRS.map { (a, b) -> abs(answers[a - 1] + answers[b - 1] - 6).toDouble() }.average()

    /** Share of the most frequent answer value. */
    fun modeShare(answers: List<Int>): Double =
        answers.groupBy { it }.maxOf { it.value.size }.toDouble() / answers.size

    fun isValid(answers: List<Int>) = answers.size == COUNT && answers.all { it in 1..5 }
}
