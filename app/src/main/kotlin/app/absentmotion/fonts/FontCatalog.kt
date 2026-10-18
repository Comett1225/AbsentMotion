package app.absentmotion.fonts

data class FontEntry(val name: FontName, val category: Int, val languages: Set<Int>)
object FontCatalog {
    val categories = linkedMapOf(1 to "Sans Serif", 2 to "Serif", 3 to "Display", 4 to "Handwriting", 5 to "Monospace")
    val languages = linkedMapOf(
        1 to "Arabic", 2 to "Armenian", 3 to "Bengali", 4 to "Chinese (Hong Kong)", 5 to "Chinese (Simplified)",
        6 to "Chinese (Traditional)", 7 to "Cyrillic", 8 to "Cyrillic Extended", 9 to "Devanagari", 10 to "Ethiopic",
        11 to "Georgian", 12 to "Greek", 13 to "Greek Extended", 14 to "Gujarati", 15 to "Gurmukhi", 16 to "Hebrew",
        17 to "Japanese", 18 to "Kannada", 19 to "Khmer", 20 to "Korean", 21 to "Lao", 22 to "Latin",
        23 to "Latin Extended", 24 to "Malayalam", 25 to "Myanmar", 26 to "Oriya", 27 to "Sinhala", 28 to "Syriac",
        29 to "Tamil", 30 to "Telugu", 31 to "Thai", 33 to "Vietnamese"
    )
    val entries: List<FontEntry> by lazy {
        families.trim().lineSequence().flatMap { line ->
            val fields = line.split('|')
            val languages = fields[2].split(',').map { it.toInt() }.toSet()
            fields[3].split(',').map { it.toInt() }.map { variant ->
                FontEntry(FontName("googlefonts", fields[0], variant % 10 * 100, variant > 9), fields[1].toInt(), languages)
            }
        }.toList()
    }
    private val families = """
Roboto|1|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Open Sans|1|7,8,12,13,16,22,23,33|3,13,4,14,5,15,6,16,7,17,8,18
Google Sans|1|2,3,7,8,9,10,11,12,13,14,15,16,19,21,22,23,24,26,27,29,30,31,33|4,14,5,15,6,16,7,17
Inter|1|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Montserrat|1|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Poppins|1|9,22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Lato|1|22,23|1,11,3,13,4,14,7,17,9,19
Noto Sans JP|1|7,17,22,23,33|1,2,3,4,5,6,7,8,9
Arimo|1|7,8,12,13,16,22,23,33|4,14,5,15,6,16,7,17
Roboto Condensed|1|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Roboto Mono|5|7,8,12,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17
Oswald|1|7,8,22,23,33|2,3,4,5,6,7
Noto Sans|1|7,8,9,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
DM Sans|1|22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Raleway|1|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Playfair Display|2|7,22,23,33|4,14,5,15,6,16,7,17,8,18,9,19
Nunito|1|7,8,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Nunito Sans|1|7,8,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Roboto Slab|2|7,8,12,13,22,23,33|1,2,3,4,5,6,7,8,9
Manrope|1|7,8,12,22,23,33|2,3,4,5,6,7,8
Rubik|1|1,7,8,16,22,23|3,13,4,14,5,15,6,16,7,17,8,18,9,19
Outfit|1|22,23|1,2,3,4,5,6,7,8,9
Ubuntu|1|7,8,12,13,22,23|3,13,4,14,5,15,7,17
Merriweather|2|7,8,22,23,33|3,13,4,14,5,15,6,16,7,17,8,18,9,19
Plus Jakarta Sans|1|8,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18
Kanit|1|22,23,31,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Bebas Neue|1|22,23|4
Work Sans|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Lora|2|7,8,22,23,33|4,14,5,15,6,16,7,17
Noto Sans KR|1|7,20,22,23,33|1,2,3,4,5,6,7,8,9
Archivo Black|1|22,23|4
Figtree|1|22,23|3,13,4,14,5,15,6,16,7,17,8,18,9,19
Noto Sans TC|1|6,7,22,23,33|1,2,3,4,5,6,7,8,9
JetBrains Mono|5|7,8,12,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18
Quicksand|1|22,23,33|3,4,5,6,7
Barlow|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
PT Sans|1|7,8,22,23|4,14,7,17
Archivo|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Mulish|1|7,8,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Jost|1|7,22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Source Sans 3|1|7,8,12,13,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Prompt|1|22,23,31,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
IBM Plex Sans|1|7,8,12,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17
Saira|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Space Grotesk|1|22,23,33|3,4,5,6,7
Bricolage Grotesque|1|22,23,33|2,3,4,5,6,7,8
Black Ops One|3|8,22,23,33|4
Karla|1|22,23|2,12,3,13,4,14,5,15,6,16,7,17,8,18
Fira Sans|1|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Libre Baskerville|2|22,23|4,14,5,15,6,16,7,17
Heebo|1|16,22,23|1,2,3,4,5,6,7,8,9
Cormorant Garamond|2|7,8,22,23,33|3,13,4,14,5,15,6,16,7,17
Share Tech|1|22|4
Smooch Sans|1|22,23,33|1,2,3,4,5,6,7,8,9
Noto Serif|2|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Barlow Condensed|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Titillium Web|1|22,23|2,12,3,13,4,14,6,16,7,17,9
Instrument Serif|2|22,23|4,14
Anton|1|22,23,33|4
Josefin Sans|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17
Inter Tight|1|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
PT Serif|2|7,8,22,23|4,14,7,17
IBM Plex Mono|5|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17
Noto Sans SC|1|5,7,22,23,33|1,2,3,4,5,6,7,8,9
Libre Franklin|1|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Inconsolata|5|22,23,33|2,3,4,5,6,7,8,9
Cairo|1|1,22,23|2,3,4,5,6,7,8,9
Mukta|1|9,22,23|2,3,4,5,6,7,8
Noto Serif JP|2|7,17,22,23,33|2,3,4,5,6,7,8,9
EB Garamond|2|7,8,12,13,22,23,33|4,14,5,15,6,16,7,17,8,18
Source Code Pro|5|7,8,12,13,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Dancing Script|4|22,23,33|4,5,6,7
Fraunces|2|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Fjalla One|1|8,22,23,33|4
Lobster Two|3|22|4,14,7,17
Sora|1|22,23|1,2,3,4,5,6,7,8
Public Sans|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Changa One|3|22|4,14
Assistant|1|16,22,23|2,3,4,5,6,7,8
Cabin|1|22,23,33|4,14,5,15,6,16,7,17
Rajdhani|1|9,22,23|3,4,5,6,7
Geist|1|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Schibsted Grotesk|1|22,23|4,14,5,15,6,16,7,17,8,18,9,19
Hind Siliguri|1|3,22,23|3,4,5,6,7
Urbanist|1|22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Bitter|2|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Caveat|4|7,8,22,23|4,5,6,7
Alfa Slab One|3|22,23,33|4
Merriweather Sans|1|8,22,23,33|3,13,4,14,5,15,6,16,7,17,8,18
Noto Sans Khmer|1|19,22,23|1,2,3,4,5,6,7,8,9
Nanum Gothic|1|20,22|4,7,8
Noto Sans Telugu|1|22,23,30|1,2,3,4,5,6,7,8,9
Lexend|1|22,23,33|1,2,3,4,5,6,7,8,9
Fredoka|1|16,22,23|3,4,5,6,7
Ramabhadra|1|22,30|4
Instrument Sans|1|22,23|4,14,5,15,6,16,7,17
Dosis|1|22,23,33|2,3,4,5,6,7,8
DM Serif Display|2|22,23|4,14
M PLUS Rounded 1c|1|7,8,12,13,16,17,22,23,33|1,3,4,5,7,8,9
Anek Telugu|1|22,23,30|1,2,3,4,5,6,7,8
Orbitron|1|22|4,5,6,7,8,9
Newsreader|2|22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18
Red Hat Display|1|22,23|3,13,4,14,5,15,6,16,7,17,8,18,9,19
Source Serif 4|2|7,8,12,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Cinzel|2|22,23|4,5,6,7,8,9
Tajawal|1|1,22|2,3,4,5,7,8,9
Oxygen|1|22,23|3,4,7
Hind|1|9,22,23|3,4,5,6,7
Google Sans Flex|1|22,23,28,33|1,2,3,4,5,6,7,8,9
Exo 2|1|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Slabo 27px|2|22,23|4
Pacifico|4|7,8,22,23,33|4
Bungee|3|22,23,33|4
Crimson Text|2|22,23,33|4,14,6,16,7,17
Overpass|1|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Geist Mono|5|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Barlow Semi Condensed|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Bodoni Moda|2|22,23|4,14,5,15,6,16,7,17,8,18,9,19
Hanken Grotesk|1|8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Teko|1|9,22,23|3,4,5,6,7
Arvo|2|22|4,14,7,17
Lobster|3|7,8,22,23,33|4
Noto Sans Arabic|1|1,22,23|1,2,3,4,5,6,7,8,9
Chakra Petch|1|22,23,31,33|3,13,4,14,5,15,6,16,7,17
PT Sans Narrow|1|7,8,22,23|4,7
Epilogue|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
DM Mono|5|22,23|3,13,4,14,5,15
Comfortaa|3|7,8,12,22,23,33|3,4,5,6,7
Abel|1|22|4
Noto Sans Thai|1|22,23,31|1,2,3,4,5,6,7,8,9
Space Mono|5|22,23,33|4,14,7,17
M PLUS 1p|1|7,8,12,13,16,17,22,23,33|1,3,4,5,7,8,9
Zen Kaku Gothic New|1|7,17,22,23|3,4,5,7,9
Almarai|1|1,22|3,4,7,8
Shadows Into Light|4|22,23|4
Lilita One|3|22,23|4
Maven Pro|1|22,23,33|4,5,6,7,8,9
Asap|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Abril Fatface|3|22,23|4
Marcellus|2|22,23|4
Archivo Narrow|1|22,23,33|4,14,5,15,6,16,7,17
Saira Condensed|1|22,23,33|1,2,3,4,5,6,7,8,9
Be Vietnam Pro|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Domine|2|22,23|4,5,6,7
Questrial|1|22,23,33|4
Zilla Slab|2|22,23|3,13,4,14,5,15,6,16,7,17
Play|1|7,8,12,22,23,33|4,7
ABeeZee|1|22,23|4,14
Onest|1|7,8,22,23,33|1,2,3,4,5,6,7,8,9
Unbounded|1|7,8,22,23,33|2,3,4,5,6,7,8,9
Great Vibes|4|7,8,13,22,23,33|4
Gravitas One|3|22|4
IBM Plex Sans Arabic|1|1,8,22,23|1,2,3,4,5,6,7
Spectral|2|7,8,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18
Sofia Sans|1|7,8,12,22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Cormorant|2|7,8,22,23,33|3,13,4,14,5,15,6,16,7,17
Albert Sans|1|22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Roboto Serif|2|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Baloo 2|3|9,22,23,33|4,5,6,7,8
Lexend Deca|1|22,23,33|1,2,3,4,5,6,7,8,9
League Spartan|1|22,23,33|1,2,3,4,5,6,7,8,9
Noto Serif TC|2|6,7,22,23,33|2,3,4,5,6,7,8,9
Exo|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Noto Sans Tamil|1|22,23,29|1,2,3,4,5,6,7,8,9
Varela Round|1|16,22,23,33|4
Permanent Marker|4|22|4
Noto Serif KR|2|7,20,22,23,33|2,3,4,5,6,7,8,9
IBM Plex Serif|2|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17
Nanum Myeongjo|2|20,22|4,7,8
Red Hat Text|1|22,23|3,13,4,14,5,15,6,16,7,17
Syne|1|12,22,23|4,5,6,7,8
Zen Maru Gothic|1|7,12,17,22,23|3,4,5,7,9
News Cycle|1|7,8,12,13,22,23,33|4,7
Geologica|1|7,8,12,22,23,33|1,2,3,4,5,6,7,8,9
Noto Serif SC|2|5,7,22,23,33|2,3,4,5,6,7,8,9
Frank Ruhl Libre|2|16,22,23|3,4,5,6,7,8,9
Indie Flower|4|22,23|4
Amiri|2|1,22,23|4,14,7,17
Satisfy|4|22|4
Slabo 13px|2|22,23|4
Doppio One|1|22,23|4
Courier Prime|5|22,23|4,14,7,17
Atkinson Hyperlegible|1|22,23|4,14,7,17
Kalam|4|9,22,23|3,4,7
Yellowtail|4|22,23|4
Rethink Sans|1|22,23|4,14,5,15,6,16,7,17,8,18
Noto Kufi Arabic|1|1,22,23|1,2,3,4,5,6,7,8,9
Alegreya Sans|1|7,8,12,13,22,23,33|1,11,3,13,4,14,5,15,7,17,8,18,9,19
Fira Sans Condensed|1|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Yanone Kaffeesatz|1|7,8,22,23,33|2,3,4,5,6,7
Luckiest Guy|3|22,23|4
Sarabun|1|22,23,31,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18
Vollkorn|2|7,8,12,22,23,33|4,14,5,15,6,16,7,17,8,18,9,19
Faustina|2|22,23,33|3,13,4,14,5,15,6,16,7,17,8,18
Righteous|3|22,23|4
Hammersmith One|1|22,23|4
Noto Sans Devanagari|1|9,22,23|1,2,3,4,5,6,7,8,9
LINE Seed JP|1|7,13,17,22,23|1,4,7,8
Crimson Pro|2|22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Shippori Mincho|2|17,22,23|4,5,6,7,8
Literata|2|7,8,12,13,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Playfair|2|7,8,22,23,33|3,13,4,14,5,15,6,16,7,17,8,18,9,19
Montserrat Alternates|1|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Quantico|1|22|4,14,7,17
Baskervville|2|22,23|4,14,5,15,6,16,7,17
Noto Nastaliq Urdu|2|1,22,23|4,5,6,7
Noto Naskh Arabic|2|1,22,23|4,5,6,7
Cardo|2|12,13,16,22,23|4,14,7
Press Start 2P|3|7,8,12,22,23|4
League Gothic|1|22,23,33|4
DM Serif Text|2|22,23|4,14
Signika|1|22,23,33|3,4,5,6,7
Asap Condensed|1|22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Catamaran|1|22,23,29|1,2,3,4,5,6,7,8,9
Rowdies|3|22,23,33|3,4,7
Bangers|3|22,23,33|4
Russo One|1|7,22,23|4
Bree Serif|2|22,23|4
Viga|1|22,23|4
Special Elite|3|22,23|4
Lexend Giga|1|22,23,33|1,2,3,4,5,6,7,8,9
Prata|2|7,8,22,33|4
Tinos|2|7,8,12,13,16,22,23,33|4,14,7,17
Alegreya|2|7,8,12,13,22,23,33|4,14,5,15,6,16,7,17,8,18,9,19
Changa|1|1,22,23|2,3,4,5,6,7,8
Patua One|3|22|4
Tenor Sans|1|7,22,23|4
Readex Pro|1|1,22,23,33|2,3,4,5,6,7
Acme|1|22|4
Khand|1|9,22,23|3,4,5,6,7
Audiowide|3|22,23|4
Zeyada|4|22,23|4
Oxanium|3|22,23|2,3,4,5,6,7,8
Golos Text|1|7,8,22,23|4,5,6,7,8,9
Alata|1|22,23,33|4
Noticia Text|2|22,23,33|4,14,7,17
Amatic SC|4|7,16,22,23,33|4,7
Comic Neue|4|22|3,13,4,14,7,17
Hind Madurai|1|22,23,29|3,4,5,6,7
Cantarell|1|22,23|4,14,7,17
IBM Plex Sans Condensed|1|8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17
Encode Sans|1|22,23,33|1,2,3,4,5,6,7,8,9
Nanum Gothic Coding|4|20,22|4,7
Noto Sans Display|1|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Gothic A1|1|7,8,12,13,20,22,23,33|1,2,3,4,5,6,7,8,9
Fira Code|5|7,8,12,13,22,23|3,4,5,6,7
Aleo|2|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Libre Caslon Text|2|22,23|4,14,7
Oleo Script|3|22,23|4,7
Chivo|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Sawarabi Mincho|2|17,22,23|4
Sanchez|2|22,23|4,14
Alexandria|1|1,22,23,33|1,2,3,4,5,6,7,8,9
Courgette|4|22,23|4
Antic Slab|2|22|4
Kumbh Sans|1|22,23|1,2,3,4,5,6,7,8,9
Fugaz One|3|22|4
Quattrocento|2|22,23|4,7
Old Standard TT|2|7,8,22,23,33|4,14,7
Zen Old Mincho|2|7,12,17,22,23|4,5,6,7,9
Crete Round|2|22,23|4,14
Sawarabi Gothic|1|7,17,22,23,33|4
Antonio|1|22,23|1,2,3,4,5,6,7
STIX Two Text|2|7,8,12,22,23,33|4,14,5,15,6,16,7,17
Philosopher|1|7,8,22,23,33|4,14,7,17
Rubik Mono One|1|7,22,23|4
Passion One|3|22,23|4,7,9
Noto Sans Mono|1|7,8,12,13,22,23,33|1,2,3,4,5,6,7,8,9
Neuton|2|22,23|2,3,4,14,7,8
Share Tech Mono|5|22|4
Gruppo|1|22,23|4
Kaushan Script|4|22,23|4
Allura|4|22,23,33|4
Noto Sans Bengali|1|3,22,23|1,2,3,4,5,6,7,8,9
Sacramento|4|22,23|4
Francois One|1|22,23,33|4
Patrick Hand|4|22,23,33|4
Google Sans Code|5|22,23,28,33|3,13,4,14,5,15,6,16,7,17,8,18
Yantramanav|1|9,22,23|1,3,4,5,7,9
VT323|5|22,23,33|4
Signika Negative|1|22,23,33|3,4,5,6,7
Chelsea Market|3|22,23|4
Vazirmatn|1|1,22,23|1,2,3,4,5,6,7,8,9
Josefin Slab|2|22|1,11,2,12,3,13,4,14,5,15,6,16,7,17
Creepster|3|22|4
Paytone One|1|22,23,33|4
BIZ UDPGothic|1|7,13,17,22,23|4,7
Sen|1|22,23|4,5,6,7,8
Tektur|3|7,8,12,22,23,33|4,5,6,7,8,9
Damion|4|22,23|4
Titan One|3|22,23|4
Forum|3|7,8,22,23|4
Martel|2|9,22,23|2,3,4,6,7,8,9
Commissioner|1|7,8,12,22,23,33|1,2,3,4,5,6,7,8,9
Bevan|2|22,23,33|4,14
Unna|2|22,23|4,14,7,17
Biryani|1|9,22,23|2,3,4,6,7,8,9
PT Sans Caption|1|7,8,22,23|4,7
Amaranth|1|22|4,14,7,17
Radio Canada|1|22,23,33|3,13,4,14,5,15,6,16,7,17
Gilda Display|2|22,23|4
Cutive Mono|5|22,23|4
Pinyon Script|4|22,23,33|4
Didact Gothic|1|7,8,12,13,22,23|4
Encode Sans Condensed|1|22,23,33|1,2,3,4,5,6,7,8,9
Dela Gothic One|3|7,12,17,22,23,33|4
Saira Extra Condensed|1|22,23,33|1,2,3,4,5,6,7,8,9
Advent Pro|1|7,8,12,22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Playfair Display SC|2|7,22,23,33|4,14,7,17,9,19
Playball|3|22,23,33|4
Istok Web|1|7,8,22,23|4,14,7,17
Delius|4|22|4
Kosugi Maru|1|7,17,22,23|4
Ubuntu Condensed|1|7,8,12,13,22,23|4
Bai Jamjuree|1|22,23,31,33|2,12,3,13,4,14,5,15,6,16,7,17
Alex Brush|4|22,23,33|4
Merienda|4|22,23,33|3,4,5,6,7,8,9
Gloria Hallelujah|4|22,23|4
M PLUS 1|1|17,22,23,33|1,2,3,4,5,6,7,8,9
Rammetto One|3|22,23|4
Michroma|1|22,23|4
Noto Sans Hebrew|1|8,13,16,22,23|1,2,3,4,5,6,7,8,9
Staatliches|3|22,23|4
El Messiri|1|1,7,22,23|4,5,6,7
Rokkitt|2|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Sofia Sans Condensed|1|7,8,12,22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Noto Sans Malayalam|1|22,23,24|1,2,3,4,5,6,7,8,9
Gabarito|3|22,23|4,5,6,7,8,9
Kameron|2|22,23|4,5,6,7
Krub|1|22,23,31,33|2,12,3,13,4,14,5,15,6,16,7,17
Tangerine|4|22|4,7
IBM Plex Sans Thai|1|8,22,23,31|1,2,3,4,5,6,7
Concert One|3|22,23|4
Poiret One|3|7,22,23|4
Quattrocento Sans|1|22,23|4,14,7,17
Noto Sans HK|1|4,7,22,23,33|1,2,3,4,5,6,7,8,9
Cinzel Decorative|3|22,23|4,7,9
Andada Pro|2|22,23,33|4,14,5,15,6,16,7,17,8,18
Libre Bodoni|2|22,23,33|4,14,5,15,6,16,7,17
Racing Sans One|3|22,23|4
Monda|1|22,23,33|4,5,6,7
PT Mono|5|7,8,22,23|4
Lalezar|1|1,22,23,33|4
Architects Daughter|4|22,23|4
Bowlby One SC|3|22,23|4
Abhaya Libre|2|22,23,27|4,5,6,7,8
Cookie|4|22|4
Italianno|4|22,23,33|4
Homemade Apple|4|22|4
Mitr|1|22,23,31,33|2,3,4,5,6,7
Yeseva One|3|7,8,22,23,33|4
Cormorant Infant|2|7,8,22,23,33|3,13,4,14,5,15,6,16,7,17
Comic Relief|3|7,12,22,23|4,7
Unica One|3|22,23,33|4
Jua|1|20,22|4
GFS Didot|2|12,13,22,33|4
Goldman|3|22,23,33|4,7
Lustria|2|22|4
Gelasio|2|22,23,33|4,14,5,15,6,16,7,17
Actor|1|22|4
Radio Canada Big|1|22,23|4,14,5,15,6,16,7,17
Vidaloka|2|22|4
Pathway Gothic One|1|22,23|4
Arsenal|1|7,8,22,23,33|4,14,7,17
Nanum Pen Script|4|20,22|4
Volkhov|2|22|4,14,7,17
Parisienne|4|22,23|4
Noto Serif Display|2|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Ubuntu Mono|5|7,8,12,13,22,23|4,14,7,17
Sofia Sans Extra Condensed|1|7,8,12,22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Host Grotesk|1|22,23|3,13,4,14,5,15,6,16,7,17,8,18
Sorts Mill Goudy|2|22,23|4,14
Black Han Sans|1|20,22|4
Reenie Beanie|4|22|4
Hind Guntur|1|22,23,30|3,4,5,6,7
Lusitana|2|22|4,7
Petrona|2|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Belleza|1|22,23|4
Sarala|1|9,22,23|4,7
DotGothic16|1|7,17,22,23|4
Fira Mono|5|7,8,12,13,22,23|4,5,7
Berkshire Swash|4|22,23|4
Caveat Brush|4|22,23|4
Shippori Mincho B1|2|17,22,23|4,5,6,7,8
Calistoga|3|22,23,33|4
Atkinson Hyperlegible Next|1|22,23|2,12,3,13,4,14,5,15,6,16,7,17,8,18
Suez One|2|16,22,23|4
Jersey 25|3|22,23|4
Pirata One|3|22,23|4
Afacad|1|8,22,23,33|4,14,5,15,6,16,7,17
Saira Semi Condensed|1|22,23,33|1,2,3,4,5,6,7,8,9
Lemonada|3|1,22,23,33|3,4,5,6,7
Sriracha|4|22,23,31,33|4
Rock Salt|4|22|4
Besley|2|22,23|4,14,5,15,6,16,7,17,8,18,9,19
Syncopate|1|22,23|4,7
Reddit Sans|1|22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Georama|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Bowlby One|3|22|4
Mrs Saint Delafield|4|22,23|4
Rozha One|2|9,22,23|4
Balsamiq Sans|3|7,8,22,23|4,14,7,17
Arapey|2|22|4,14
Amita|4|9,22,23|4,7
Alumni Sans|1|7,8,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Rye|3|22,23|4
Averia Serif Libre|3|22|3,13,4,14,7,17
Familjen Grotesk|1|22,23,33|4,14,5,15,6,16,7,17
Pixelify Sans|3|7,22,23|4,5,6,7
Cuprum|1|7,8,22,23,33|4,14,5,15,6,16,7,17
Julius Sans One|1|22,23|4
M PLUS 2|1|17,22,23,33|1,2,3,4,5,6,7,8,9
Alice|2|7,8,22,23|4
Blinker|1|22,23|1,2,3,4,6,7,8,9
Varela|1|22,23|4
Anonymous Pro|5|7,12,22,23|4,14,7,17
Reem Kufi|1|1,22,23,33|4,5,6,7
BIZ UDGothic|1|7,13,17,22,23|4,7
Nothing You Could Do|4|22|4
Mona Sans|1|22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Leckerli One|4|22|4
Coda|3|22,23|4,8
Mada|1|1,22,23|2,3,4,5,6,7,8,9
Carlito|1|7,8,12,13,22,23,33|4,14,7,17
Jura|1|7,8,12,13,22,23,33|3,4,5,6,7
Noto Serif Bengali|2|3,22,23|1,2,3,4,5,6,7,8,9
Bad Script|4|7,8,22,23,33|4
Mukta Malar|1|22,23,29|2,3,4,5,6,7,8
Fira Sans Extra Condensed|1|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Zen Kaku Gothic Antique|1|7,17,22,23|3,4,5,7,9
Kaisei Decol|2|7,17,22,23|4,5,7
Eczar|2|9,12,13,22,23|4,5,6,7,8
Anek Bangla|1|3,22,23|1,2,3,4,5,6,7,8
Belanosima|1|22,23|4,6,7
Ultra|2|22,23|4
Pangolin|4|7,8,22,23,33|4
Taviraj|2|22,23,31,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Gloock|2|8,22,23|4
Squada One|3|22|4
Italiana|1|22|4
Baloo Bhai 2|3|14,22,23,33|4,5,6,7,8
Secular One|1|16,22,23|4
Red Hat Mono|5|22,23|3,13,4,14,5,15,6,16,7,17
Monoton|3|22,23|4
Handlee|4|22|4
Mate|2|22,23|4,14
Funnel Sans|1|22,23|3,13,4,14,5,15,6,16,7,17,8,18
Marck Script|4|7,22,23|4
IBM Plex Sans JP|1|7,17,22,23|1,2,3,4,5,6,7
Limelight|3|22,23|4
Six Caps|1|22,23|4
K2D|1|22,23,31,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18
Mandali|1|22,30|4
Cousine|5|7,8,12,13,16,22,23,33|4,14,7,17
Germania One|3|22|4
Noto Sans Myanmar|1|22,23,25|1,2,3,4,5,6,7,8,9
Pridi|2|22,23,31,33|2,3,4,5,6,7
Economica|1|22,23|4,14,7,17
Mr Dafoe|4|22,23|4
Carter One|3|22|4
Eater|3|22,23|4
Itim|4|22,23,31,33|4
Pragati Narrow|1|9,22,23|4,7
Anuphan|1|22,23,31,33|1,2,3,4,5,6,7
Murecho|1|7,8,12,17,22,23|1,2,3,4,5,6,7,8,9
Dongle|1|20,22,23,33|3,4,7
Chango|3|22,23|4
Noto Sans Georgian|1|8,11,13,22,23|1,2,3,4,5,6,7,8,9
Wix Madefor Display|1|7,8,22,23,33|4,5,6,7,8
Ms Madi|4|22,23,33|4
Gudea|1|22,23|4,14,7
Noto Sans Gujarati|1|14,22,23|1,2,3,4,5,6,7,8,9
Sofia|4|22|4
Special Gothic Expanded One|1|22,23|4
Potta One|3|17,22,23,33|4
Do Hyeon|1|20,22|4
Wix Madefor Text|1|7,8,22,23,33|4,14,5,15,6,16,7,17,8,18
Skranji|3|22,23|4,7
Darker Grotesque|1|22,23,33|3,4,5,6,7,8,9
Fahkwang|1|22,23,31,33|2,12,3,13,4,14,5,15,6,16,7,17
Hind Vadodara|1|14,22,23|3,4,5,6,7
Hachi Maru Pop|4|7,17,22,23|4
Gantari|1|22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Andika|1|7,8,22,23,33|4,14,7,17
Knewave|3|22,23|4
REM|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Funnel Display|3|22,23|3,4,5,6,7,8
IM Fell English|2|22|4,14
Khula|1|9,22,23|3,4,6,7,8
Gochi Hand|4|22|4
Caprasimo|3|22,23|4
Chewy|3|22|4
Tiro Bangla|2|3,22,23|4,14
Silkscreen|3|22,23|4,7
Niramit|1|22,23,31,33|2,12,3,13,4,14,5,15,6,16,7,17
Palanquin|1|9,22,23|1,2,3,4,5,6,7
MuseoModerno|3|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Akshar|1|9,22,23|3,4,5,6,7
Alegreya Sans SC|1|7,8,12,13,22,23,33|1,11,3,13,4,14,5,15,7,17,8,18,9,19
Covered By Your Grace|4|22,23|4
Aboreto|3|22,23|4
Londrina Solid|3|22|1,3,4,9
Adamina|2|22|4
Basic|1|22,23|4
Anek Latin|1|22,23,33|1,2,3,4,5,6,7,8
Ma Shan Zheng|4|5,22|4
Martel Sans|1|9,22,23|2,3,4,6,7,8,9
Marcellus SC|2|22,23|4
Ruda|1|7,22,23,33|4,5,6,7,8,9
Charm|4|22,23,31,33|4,7
Cabin Condensed|1|22,23,33|4,5,6,7
Geo|1|22|4,14
Tomorrow|1|22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Original Surfer|3|22,23|4
Fustat|1|1,22,23|2,3,4,5,6,7,8
Azeret Mono|5|22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Grandstander|3|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Ovo|2|22|4
Big Shoulders|3|22,23,33|1,2,3,4,5,6,7,8,9
Angkor|3|19,22|4
Parkinsans|1|22,23|3,4,5,6,7,8
Kreon|2|22,23|3,4,5,6,7
Sansita|1|22,23|4,14,7,17,8,18,9,19
Qwitcher Grypen|4|22,23,33|4,7
Shrikhand|3|14,22,23|4
Days One|1|7,22|4
Noto Sans Sinhala|1|22,23,27|1,2,3,4,5,6,7,8,9
Barriecito|3|22,23,33|4
Libre Caslon Display|2|22,23|4
Ropa Sans|1|22,23|4,14
Cabin Sketch|3|22|4,7
IBM Plex Sans KR|1|20,22,23|1,2,3,4,5,6,7
Baloo Da 2|3|3,22,23,33|4,5,6,7,8
Yuji Mai|2|7,17,22,23|4
Cedarville Cursive|4|22|4
BenchNine|1|22,23|3,4,7
Poller One|3|22|4
Niconne|4|22,23|4
Aclonica|1|22,23|4
Proza Libre|1|22,23|4,14,5,15,6,16,7,17,8,18
Neucha|4|7,22|4
Electrolize|1|22|4
Gowun Batang|2|20,22,23,33|4,7
La Belle Aurore|4|22,23|4
Spline Sans|1|22,23|3,4,5,6,7
Yrsa|2|22,23,33|3,13,4,14,5,15,6,16,7,17
UnifrakturMaguntia|3|22|4
Krona One|1|22,23|4
Overpass Mono|5|7,8,22,23,33|3,4,5,6,7
Noto Sans Kannada|1|18,22,23|1,2,3,4,5,6,7,8,9
Pontano Sans|1|22,23|3,4,5,6,7
Spinnaker|1|22,23|4
Noto Sans Meetei Mayek|1|22,23|1,2,3,4,5,6,7,8,9
Baloo Bhaijaan 2|3|1,22,23,33|4,5,6,7,8
Noto Sans Gurmukhi|1|15,22,23|1,2,3,4,5,6,7,8,9
Kiwi Maru|2|7,17,22,23|3,4,5
Anaheim|1|22,23,33|4,5,6,7,8
Castoro|2|22,23|4,14
Glory|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18
Averia Libre|3|22|3,13,4,14,7,17
Boogaloo|3|22|4
Zalando Sans|1|22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Glegoo|2|9,22,23|4,7
Stardos Stencil|3|22|4,7
Rufina|2|22,23|4,7
Charis SIL|2|7,8,22,23,33|4,14,7,17
Graduate|2|22|4
Tilt Warp|3|22,23,33|4
Shadows Into Light Two|4|22,23|4
Tiro Devanagari Hindi|2|9,22,23|4,14
Chonburi|3|22,23,31,33|4
Aldrich|1|22|4
ZCOOL XiaoWei|1|5,22|4
Young Serif|2|22,23|4
Playpen Sans|4|7,8,12,22,23,33|1,2,3,4,5,6,7,8
Vina Sans|3|22,23,33|4
Oooh Baby|4|22,23,33|4
Lexend Exa|1|22,23,33|1,2,3,4,5,6,7,8,9
Just Another Hand|4|22,23|4
TikTok Sans|1|7,8,12,22,23,33|3,4,5,6,7,8,9
Palanquin Dark|1|9,22,23|4,5,6,7
Trirong|2|22,23,31,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Klee One|4|7,13,17,22,23|4,6
Pattaya|1|7,22,23,31,33|4
Reddit Sans Condensed|1|22,23,33|2,3,4,5,6,7,8,9
Fragment Mono|5|8,22,23|4,14
Cantata One|2|22,23|4
Rakkas|3|1,22,23|4
Kosugi|1|7,17,22,23|4
Chivo Mono|5|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Afacad Flux|1|22,23,33|1,2,3,4,5,6,7,8,9
Fredericka the Great|3|22,23|4
Yatra One|3|9,22,23|4
ZCOOL KuaiLe|1|5,22|4
Bellefair|2|16,22,23|4
Judson|2|22,23,33|4,14,7
Sintony|1|22,23|4,7
Gowun Dodum|1|20,22,23,33|4
Mochiy Pop One|1|17,22|4
Caudex|2|12,13,22,23,33|4,14,7,17
Oranienbaum|2|7,8,22,23|4
Inria Serif|2|22,23|3,13,4,14,7,17
Mali|4|22,23,31,33|2,12,3,13,4,14,5,15,6,16,7,17
Brygada 1918|2|7,8,12,22,23,33|4,14,5,15,6,16,7,17
Armata|1|22,23|4
Fondamento|4|22,23|4,14
Seaweed Script|3|22,23|4
Pathway Extreme|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Zalando Sans Expanded|1|22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Athiti|1|22,23,31,33|2,3,4,5,6,7
Karma|2|9,22,23|3,4,5,6,7
Cormorant SC|2|7,8,22,23,33|3,4,5,6,7
Caladea|2|22,23|4,14,7,17
Hepta Slab|2|22,23,33|1,2,3,4,5,6,7,8,9
Sigmar One|3|22,23,33|4
Petit Formal Script|4|22,23|4
Radley|2|22,23|4,14
DynaPuff|3|8,22,23|4,5,6,7
Cal Sans|1|22,23,33|4
Corben|3|22,23|4,7
Noto Sans Oriya|1|22,23,26|1,2,3,4,5,6,7,8,9
Telex|1|22,23|4
Livvic|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,9,19
Spline Sans Mono|5|22,23|3,13,4,14,5,15,6,16,7,17
Candal|1|22|4
Bona Nova SC|2|7,8,12,16,22,23,33|4,14,7
PT Serif Caption|2|7,8,22,23|4,14
Ubuntu Sans|1|7,8,12,13,22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18
Rampart One|3|7,17,22,23|4
Sedgwick Ave Display|4|22,23,33|4
Metamorphous|3|22,23|4
Podkova|2|7,8,22,23,33|4,5,6,7,8
Alef|1|16,22|4,7
Aref Ruqaa|2|1,22,23|4,7
Allerta Stencil|1|22|4
Lateef|2|1,22,23|2,3,4,5,6,7,8
Sofia Sans Semi Condensed|1|7,8,12,22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Antic Didone|2|22|4
Martian Mono|5|7,8,22,23|1,2,3,4,5,6,7,8
Herr Von Muellerhoff|4|22,23|4
Metrophobic|1|22,23,33|4
Share|1|22,23|4,14,7,17
Yusei Magic|1|17,22,23|4
Nanum Brush Script|4|20,22|4
Shantell Sans|3|7,8,22,23,33|3,13,4,14,5,15,6,16,7,17,8,18
Hina Mincho|2|7,17,22,23,33|4
Laila|2|9,22,23|3,4,5,6,7
Halant|2|9,22,23|3,4,5,6,7
Hahmlet|2|20,22,23,33|1,2,3,4,5,6,7,8,9
Asul|2|22|4,7
Gabriela|2|7,8,22,23|4
Red Rose|3|22,23,33|3,4,5,6,7
IM Fell English SC|2|22|4
Bayon|1|19,22|4
AR One Sans|1|22,23,33|4,5,6,7
Lexend Zetta|1|22,23,33|1,2,3,4,5,6,7,8,9
ADLaM Display|3|22,23|4
Chiron GoRound TC|1|6,7,8,22,23,33|2,3,4,5,6,7,8,9
Arbutus Slab|2|22,23|4
Poetsen One|3|22,23|4
Anton SC|1|22,23,33|4
Grand Hotel|4|22,23|4
Fjord One|2|22|4
Baloo Thambi 2|3|22,23,29,33|4,5,6,7,8
Ysabeau Office|1|7,8,12,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Ibarra Real Nova|2|22,23|4,14,5,15,6,16,7,17
Shippori Antique|1|17,22,23|4
Delicious Handrawn|4|22,23|4
Asap Sharp|1|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
BIZ UDPMincho|2|7,13,17,22,23|4,7
Agbalumo|3|8,10,22,23,33|4
Wallpoet|3|22|4
Annie Use Your Telescope|4|22,23|4
Special Gothic|1|22,23|4,5,6,7
Anybody|3|22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Sniglet|3|22,23|4,8
Rosario|1|22,23,33|3,13,4,14,5,15,6,16,7,17
Style Script|4|22,23,33|4
RocknRoll One|1|17,22,23|4
Kodchasan|1|22,23,31,33|2,12,3,13,4,14,5,15,6,16,7,17
Cormorant Upright|2|22,23,33|3,4,5,6,7
Inria Sans|1|22,23|3,13,4,14,7,17
Bentham|2|22,23|4
Mansalva|4|12,22,23,33|4
Kantumruy Pro|1|19,22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17
Enriqueta|2|22,23|4,5,6,7
Alatsi|1|8,22,23,33|4
Markazi Text|2|1,22,23,33|4,5,6,7
Overlock|3|22,23|4,14,7,17,9,19
Nobile|1|7,22,23|4,14,5,15,7,17
Alegreya SC|2|7,8,12,13,22,23,33|4,14,5,15,7,17,8,18,9,19
Noto Sans Ol Chiki|1|22,23|4,5,6,7
Rubik Spray Paint|3|7,8,16,22,23|4
Grenze Gotisch|3|22,23,33|1,2,3,4,5,6,7,8,9
Nixie One|3|22|4
Hedvig Letters Serif|2|22,23|4
MedievalSharp|3|22,23|4
Noto Serif Devanagari|2|9,22,23|1,2,3,4,5,6,7,8,9
Just Me Again Down Here|4|22,23|4
Norican|4|22,23|4
Allerta|1|22|4
Amarante|3|22,23|4
Coming Soon|4|22|4
Yuji Syuku|2|7,17,22,23|4
Cormorant Unicase|2|7,8,22,23,33|3,4,5,6,7
Average Sans|1|22,23|4
Arizonia|4|22,23,33|4
Quintessential|4|22,23|4
Monomaniac One|1|17,22,23|4
Kaisei Opti|2|7,17,22,23|4,5,7
Turret Road|3|22,23|2,3,4,5,7,8
Schoolbell|4|22|4
Goudy Bookletter 1911|2|22|4
Montagu Slab|2|22,23,33|1,2,3,4,5,6,7
Marmelad|1|7,8,22,23,33|4
Koulen|3|19,22|4
Faster One|3|22,23|4
Major Mono Display|5|22,23,33|4
Love Ya Like A Sister|3|22,23|4
Vast Shadow|2|22|4
Oxygen Mono|5|22,23|4
Calligraffitti|4|22|4
Libertinus Serif|2|7,8,12,13,16,22,23,33|4,14,6,16,7,17
Mouse Memoirs|1|22,23|4
Fuzzy Bubbles|4|22,23,33|4,7
Tenali Ramakrishna|1|22,30|4
Dawning of a New Day|4|22|4
Irish Grover|3|22|4
Yesteryear|4|22,23|4
Rubik Doodle Shadow|3|7,8,16,22,23|4
Sancreek|3|22,23|4
Amiko|1|9,22,23|4,6,7
Corinthia|4|22,23,33|4,7
Marvel|1|22|4,14,7,17
BioRhyme|2|22,23|2,3,4,5,6,7,8
Tilt Neon|3|22,23,33|4
Zalando Sans SemiExpanded|1|22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Kablammo|3|7,8,22,23,33|4
Zen Dots|3|22,23|4
Bubblegum Sans|3|22,23|4
Rancho|4|22|4
Lexend Peta|1|22,23,33|1,2,3,4,5,6,7,8,9
Waiting for the Sunrise|4|22,23|4
Gaegu|4|20,22|3,4,7
New Rocker|3|22,23|4
Kristi|4|22|4
IM Fell DW Pica|2|22|4,14
Zen Kurenaido|1|7,12,17,22,23|4
Meddon|4|22,23|4
Noto Serif Khojki|2|22,23|4,5,6,7
IBM Plex Sans Hebrew|1|8,16,22,23|1,2,3,4,5,6,7
Recursive|1|8,22,23,33|3,4,5,6,7,8,9
Monsieur La Doulaise|4|22,23|4
Bungee Inline|3|22,23,33|4
Fasthand|3|19,22|4
Voltaire|1|22,23,33|4
Over the Rainbow|4|22,23|4
Rubik Glitch|3|7,8,16,22,23|4
Carrois Gothic|1|22|4
Hi Melody|4|20,22|4
Mountains of Christmas|3|22|4,7
Libertinus Sans|1|7,8,12,13,22,23,33|4,14,7
B612 Mono|5|22|4,14,7,17
Allison|4|22,23,33|4
Anek Devanagari|1|9,22,23|1,2,3,4,5,6,7,8
ZCOOL QingKe HuangYou|1|5,22|4
Akatab|1|22,23|4,5,6,7,8,9
Trocchi|2|22,23|4
Contrail One|3|22|4
Lekton|5|22,23|4,14,7
Scheherazade New|2|1,22,23|4,5,6,7
Rouge Script|4|22|4
Hurricane|4|22,23,33|4
Metal Mania|3|22,23|4
Balthazar|2|22|4
Piazzolla|2|7,8,12,13,22,23,33|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Nova Mono|5|12,22,23|4
Meow Script|4|22,23,33|4
Copse|2|22|4
Inclusive Sans|1|22,23,33|3,13,4,14,5,15,6,16,7,17
David Libre|2|16,22,23,33|4,5,7
Beth Ellen|4|22|4
Noto Serif Thai|2|22,23,31|1,2,3,4,5,6,7,8,9
Bungee Shade|3|22,23,33|4
Spectral SC|2|7,8,22,23,33|2,12,3,13,4,14,5,15,6,16,7,17,8,18
Mukta Mahee|1|15,22,23|2,3,4,5,6,7,8
Train One|3|7,17,22,23|4
Sometype Mono|5|22,23|4,14,5,15,6,16,7,17
Zen Antique Soft|2|7,12,17,22,23|4
Square Peg|4|22,23,33|4
Bellota Text|3|7,22,23,33|3,13,4,14,7,17
Almendra|2|22,23|4,14,7,17
Zen Antique|2|7,12,17,22,23|4
Maitree|2|22,23,31,33|2,3,4,5,6,7
Noto Serif Georgian|2|11,22,23|1,2,3,4,5,6,7,8,9
Birthstone|4|22,23,33|4
Alike|2|22,23|4
Rochester|4|22|4
Ribeye|3|22,23|4
Gotu|1|9,22,23,33|4
Ephesis|4|22,23,33|4
Jockey One|1|22,23|4
Fanwood Text|2|22,23|4,14
Phudu|3|8,22,23,33|3,4,5,6,7,8,9
Nova Square|3|22,23|4
Slackey|3|22|4
Boldonse|3|22,23|4
Megrim|3|22,23|4
B612|1|22|4,14,7,17
Stick No Bills|1|22,23,27|2,3,4,5,6,7,8
Iceberg|3|22|4
Tienne|2|22|4,7,9
Scada|1|7,8,22,23|4,14,7,17
Frijole|3|22|4
Aguafina Script|4|22,23|4
Kadwa|2|9,22|4,7
Zain|1|1,22|2,3,13,4,14,7,8,9
Cambay|1|9,22,23|4,14,7,17
Moon Dance|4|22,23,33|4
Manjari|1|22,23,24|1,4,7
Magra|1|22,23|4,7
Encode Sans Expanded|1|22,23,33|1,2,3,4,5,6,7,8,9
Song Myung|2|20,22|4
Baloo Tamma 2|3|18,22,23,33|4,5,6,7,8
Platypi|2|22,23,33|3,13,4,14,5,15,6,16,7,17,8,18
Hanuman|2|19,22|1,2,3,4,5,6,7,8,9
Gulzar|2|1,22,23|4
Buenard|2|22,23|4,5,6,7
Battambang|3|19,22|1,3,4,7,9
KoHo|1|22,23,31,33|2,12,3,13,4,14,5,15,6,16,7,17
Bagel Fat One|3|20,22,23|4
Arima|3|12,13,22,23,24,29,33|1,2,3,4,5,6,7
Encode Sans Semi Condensed|1|22,23,33|1,2,3,4,5,6,7,8,9
Inknut Antiqua|2|9,22,23|3,4,5,6,7,8,9
Finger Paint|3|22|4
Special Gothic Condensed One|1|22,23|4
Chicle|3|22,23|4
Capriola|1|22,23|4
Gurajada|1|22,30|4
Freehand|3|19,22|4
Syne Mono|5|22,23|4
Eagle Lake|4|22,23|4
Rubik Dirt|3|7,8,16,22,23|4
Mukta Vaani|1|14,22,23|2,3,4,5,6,7,8
Prosto One|3|7,22,23|4
Croissant One|3|22,23|4
Miriam Libre|1|16,22,23|4,5,6,7
Cutive|2|22,23|4
Asta Sans|1|20,22|3,4,5,6,7,8
Ruwudu|2|1,22,23|4,5,6,7
Average|2|22,23|4
Poly|2|22,23|4,14
Bodoni Moda SC|2|22,23|4,14,5,15,6,16,7,17,8,18,9,19
Sue Ellen Francisco|4|22|4
Qahiri|1|1,22|4
Sansita Swashed|3|22,23,33|3,4,5,6,7,8,9
Kavivanar|4|22,23,29|4
Vibur|4|22|4
Jomhuria|3|1,22,23|4
Padauk|1|22,23,25|4,7
Sour Gummy|1|22,23|1,11,2,12,3,13,4,14,5,15,6,16,7,17,8,18,9,19
Carattere|4|22,23,33|4
Mr De Haviland|4|22,23|4
Noto Sans Ethiopic|1|10,22,23|1,2,3,4,5,6,7,8,9
Puritan|1|22|4,14,7,17
Noto Sans Thai Looped|1|22,23,31|1,2,3,4,5,6,7,8,9
Maname|2|22,23,27,33|4
Fauna One|2|22,23|4
Quando|2|22,23|4
Coustard|2|22,23|4,9
Jaldi|1|9,22,23|4,7
Belgrano|2|22|4
Sunflower|1|20,22|3,5,7
Ruslan Display|3|7,22,23|4
Teachers|1|13,22,23|4,14,5,15,6,16,7,17,8,18
Xanh Mono|5|22,23,33|4,14
Happy Monkey|3|22,23|4
Rasa|2|14,22,23,33|3,13,4,14,5,15,6,16,7,17
Aladin|3|22,23|4
Freeman|3|22,23,33|4
Crafty Girls|4|22|4
Kaisei Tokumin|2|7,17,22,23|4,5,7,8
Kurale|2|7,8,9,22,23|4
Numans|1|22|4
"""
}
