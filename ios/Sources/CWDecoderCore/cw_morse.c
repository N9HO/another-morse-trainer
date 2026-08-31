/* cw_morse.c  --  Morse pattern table + linear resolver */
#include "cw_morse.h"
#include <string.h>

typedef struct { const char *pat; const char *out; } cw_entry_t;

/* Standard ITU Morse. For patterns shared by a character and a prosign
 * (e.g. ".-.-." is both '+' and <AR>), the everyday character is used.
 * A few unambiguous prosigns are included as <..> text. */
static const cw_entry_t TABLE[] = {
    /* letters */
    {".-",    "A"}, {"-...",  "B"}, {"-.-.",  "C"}, {"-..",   "D"},
    {".",     "E"}, {"..-.",  "F"}, {"--.",   "G"}, {"....",  "H"},
    {"..",    "I"}, {".---",  "J"}, {"-.-",   "K"}, {".-..",  "L"},
    {"--",    "M"}, {"-.",    "N"}, {"---",   "O"}, {".--.",  "P"},
    {"--.-",  "Q"}, {".-.",   "R"}, {"...",   "S"}, {"-",     "T"},
    {"..-",   "U"}, {"...-",  "V"}, {".--",   "W"}, {"-..-",  "X"},
    {"-.--",  "Y"}, {"--..",  "Z"},
    /* digits */
    {"-----", "0"}, {".----", "1"}, {"..---", "2"}, {"...--", "3"},
    {"....-", "4"}, {".....", "5"}, {"-....", "6"}, {"--...", "7"},
    {"---..", "8"}, {"----.", "9"},
    /* punctuation */
    {".-.-.-", "."}, {"--..--", ","}, {"..--..", "?"}, {".----.", "'"},
    {"-.-.--", "!"}, {"-..-.",  "/"}, {"-.--.",  "("}, {"-.--.-", ")"},
    {".-...",  "&"}, {"---...", ":"}, {"-.-.-.", ";"}, {"-...-",  "="},
    {".-.-.",  "+"}, {"-....-", "-"}, {"..--.-", "_"}, {".-..-.", "\""},
    {"...-..-", "$"}, {".--.-.", "@"},
    /* unambiguous prosigns */
    {"...-.-",  "<SK>"},   /* end of contact            */
    {"-...-.-", "<BK>"},   /* break                     */
    {"...-.",   "<SN>"},   /* understood / VE           */
    {".-.-",    "<AA>"},   /* new line                  */
};

#define TABLE_LEN ((int)(sizeof(TABLE)/sizeof(TABLE[0])))

const char *cw_morse_lookup(const char *pattern)
{
    if (!pattern || !pattern[0]) return NULL;
    for (int i = 0; i < TABLE_LEN; ++i)
        if (strcmp(pattern, TABLE[i].pat) == 0)
            return TABLE[i].out;
    return NULL;
}
