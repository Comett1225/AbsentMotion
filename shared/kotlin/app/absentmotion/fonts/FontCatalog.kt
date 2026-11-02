package app.absentmotion.fonts

data class FontEntry(val name: FontName, val category: Int, val languages: Set<Int>, val bundled: Boolean = false)
object FontCatalog {
    val categories = linkedMapOf(1 to "Sans Serif", 2 to "Serif", 3 to "Display", 4 to "Handwriting", 5 to "Monospace")
    val languages = linkedMapOf(
        1 to "Arabic", 3 to "Bengali", 5 to "Chinese (Simplified)", 6 to "Chinese (Traditional)", 7 to "Cyrillic",
        8 to "Cyrillic Extended", 9 to "Devanagari", 12 to "Greek", 13 to "Greek Extended", 16 to "Hebrew",
        17 to "Japanese", 20 to "Korean", 22 to "Latin", 23 to "Latin Extended", 31 to "Thai", 33 to "Vietnamese"
    )
    val entries: List<FontEntry> by lazy {
        families.trim().lineSequence().flatMap { line ->
            val fields = line.split('|')
            val languages = fields[2].split(',').map { it.toInt() }.toSet()
            fields[3].split(',').map { it.toInt() }.map { variant ->
                FontEntry(FontName("googlefonts", fields[0], variant % 10 * 100, variant > 9), fields[1].toInt(), languages, fields[4] == "1")
            }
        }.toList()
    }
    private val families = """
Roboto|1|7,22,23,33|4,7|1
Open Sans|1|7,22,23,33|4,7|1
Inter|1|7,22,23,33|4,7|1
Montserrat|1|7,22,23,33|4,7|1
Poppins|1|9,22,23|4,7|1
Roboto Mono|5|7,22,23,33|4,7|1
Oswald|1|7,22,23,33|4,7|1
DM Sans|1|22,23|4,7|1
Raleway|1|7,22,23,33|4,7|1
Playfair Display|2|7,22,23|4,7|1
Nunito|1|7,22,23,33|4,7|1
Roboto Slab|2|7,22,23,33|4,7|1
Rubik|1|7,22,23|4,7|1
Manrope|1|7,22,23,33|4,7|1
Kanit|1|22,23,31,33|4,7|1
Work Sans|1|22,23,33|4,7|1
Lora|2|7,22,23|4,7|1
Bebas Neue|3|22,23|4|1
Quicksand|1|22,23,33|4,7|1
JetBrains Mono|5|7,22,23,33|4,7|1
Mulish|1|7,22,23,33|4,7|1
Prompt|1|22,23,31,33|4,7|1
Noto Serif|2|7,22,23,33|4,7|1
Black Ops One|3|22,23|4|1
Anton|3|22,23,33|4|1
Dancing Script|4|22,23,33|4,7|1
Cairo|1|1,22,23|4,7|1
Bitter|2|7,22,23,33|4,7|1
DM Serif Display|2|22,23|4|1
Caveat|4|7,22,23|4,7|1
Alfa Slab One|3|22,23,33|4|1
Fredoka|1|22|4,7|1
Orbitron|1|22|4,7|1
Hind|1|9,22,23|4,7|1
Cinzel|2|22,23|4,7|1
Tajawal|1|1,22|4,7|1
Pacifico|4|7,22,23,33|4|1
Bungee|3|22,23,33|4|1
Lobster|3|7,22,23|4|1
Comfortaa|3|7,22,23,33|4,7|1
Space Mono|5|22,23,33|4,7|1
Satisfy|4|22|4|1
Great Vibes|4|7,22,23,33|4|1
Abril Fatface|3|22,23|4|1
Permanent Marker|4|22|4|1
Press Start 2P|3|7,22,23|4|1
Righteous|3|22,23|4|1
Amatic SC|4|7,22,23,33|4,7|1
Bangers|3|22,23,33|4|1
Kaushan Script|4|22,23|4|1
Lato|1|22,23|4,7|0
Noto Sans JP|1|7,17,22,23,33|4,7|0
Arimo|1|7,8,12,13,16,22,23,33|4,7|0
Roboto Condensed|1|7,8,12,13,22,23,33|4,7|0
Noto Sans|1|7,8,9,12,13,22,23,33|4,7|0
Nunito Sans|1|7,8,22,23,33|4,7|0
Ubuntu|1|7,8,12,13,22,23|4,7|0
Outfit|1|22,23|4,7|0
Merriweather|2|7,8,22,23,33|4,7|0
Plus Jakarta Sans|1|8,22,23,33|4,7|0
Noto Sans KR|1|7,20,22,23,33|4,7|0
Figtree|1|22,23|4,7|0
Archivo Black|1|22,23|4|0
Archivo|1|22,23,33|4,7|0
PT Sans|1|7,8,22,23|4,7|0
Noto Sans TC|1|6,7,22,23,33|4,7|0
Source Sans 3|1|7,8,12,13,22,23,33|4,7|0
Barlow|1|22,23,33|4,7|0
IBM Plex Sans|1|7,8,12,22,23,33|4,7|0
Inconsolata|5|22,23,33|4,7|0
Jost|1|7,22,23|4,7|0
Space Grotesk|1|22,23,33|4,7|0
Saira|1|22,23,33|4,7|0
Libre Baskerville|2|22,23|4,7|0
Fira Sans|1|7,8,12,13,22,23,33|4,7|0
Karla|1|22,23|4,7|0
Bricolage Grotesque|1|22,23,33|4,7|0
Heebo|1|16,22,23|4,7|0
Cormorant Garamond|2|7,8,22,23,33|4,7|0
Titillium Web|1|22,23|4,7|0
Source Code Pro|5|7,8,12,13,22,23,33|4,7|0
IBM Plex Mono|5|7,8,22,23,33|4,7|0
Share Tech|1|22|4|0
Barlow Condensed|1|22,23,33|4,7|0
Smooch Sans|1|22,23,33|4,7|0
PT Serif|2|7,8,22,23|4,7|0
Instrument Serif|2|22,23|4|0
Noto Sans SC|1|5,7,22,23,33|4,7|0
EB Garamond|2|7,8,12,13,22,23,33|4,7|0
Fraunces|2|22,23,33|4,7|0
Mukta|1|9,22,23|4,7|0
Noto Serif JP|2|7,17,22,23,33|4,7|0
Lobster Two|3|22|4,7|0
Changa One|3|22|4|0
Hind Siliguri|1|3,22,23|4,7|0
Newsreader|2|22,23,33|4,7|0
Source Serif 4|2|7,8,12,22,23,33|4,7|0
Zeyada|4|22,23|4|0
Slabo 27px|2|22,23|4|0
Geist Mono|5|7,8,22,23,33|4,7|0
Crimson Text|2|22,23,33|4,7|0
Arvo|2|22|4,7|0
Bodoni Moda|2|22,23|4,7|0
DM Mono|5|22,23|4|0
Domine|2|22,23|4,7|0
Shadows Into Light|4|22,23|4|0
Zilla Slab|2|22,23|4,7|0
Lilita One|3|22,23|4|0
Marcellus|2|22,23|4|0
IBM Plex Serif|2|7,8,22,23,33|4,7|0
Spectral|2|7,8,22,23,33|4,7|0
Cormorant|2|7,8,22,23,33|4,7|0
Baloo 2|3|9,22,23,33|4,7|0
Gravitas One|3|22|4|0
Oleo Script|3|22,23|4,7|0
Indie Flower|4|22,23|4|0
Kalam|4|9,22,23|4,7|0
Fira Code|5|7,8,12,13,22,23|4,7|0
Courier Prime|5|22,23|4,7|0
Titan One|3|22,23|4|0
Delius|4|22|4|0
Luckiest Guy|3|22,23|4|0
Yellowtail|4|22,23|4|0
Comic Neue|4|22|4,7|0
Rowdies|3|22,23,33|4,7|0
Patua One|3|22|4|0
Allura|4|22,23,33|4|0
Audiowide|3|22,23|4|0
Creepster|3|22|4|0
Fugaz One|3|22|4|0
Patrick Hand|4|22,23,33|4|0
Sacramento|4|22,23|4|0
Courgette|4|22,23|4|0
Passion One|3|22,23|4,7|0
Special Elite|3|22,23|4|0
Nanum Gothic Coding|4|20,22|4,7|0
Chelsea Market|3|22,23|4|0
Oxanium|3|22,23|4,7|0
Share Tech Mono|5|22|4|0
Tangerine|4|22|4,7|0
VT323|5|22,23,33|4|0
Gabarito|3|22,23|4,7|0
Alex Brush|4|22,23,33|4|0
Pinyon Script|4|22,23,33|4|0
Dela Gothic One|3|7,12,17,22,23,33|4|0
Architects Daughter|4|22,23|4|0
Forum|3|7,8,22,23|4|0
Homemade Apple|4|22|4|0
PT Mono|5|7,8,22,23|4|0
Gloria Hallelujah|4|22,23|4|0
"""
}
