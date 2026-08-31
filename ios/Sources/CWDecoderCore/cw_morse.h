/*
 * cw_morse.h  --  Morse element-pattern -> text resolver
 *
 * Patterns are strings of '.' (dit) and '-' (dah), e.g. ".-" for 'A'.
 */
#ifndef CW_MORSE_H
#define CW_MORSE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Return the decoded text for a dot/dash pattern, or NULL if unknown.
 * Returned pointer is to static storage; do not free. */
const char *cw_morse_lookup(const char *pattern);

#ifdef __cplusplus
}
#endif

#endif /* CW_MORSE_H */
