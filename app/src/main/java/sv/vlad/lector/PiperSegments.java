package sv.vlad.lector;

/** Short, bounded synthesis units; offsets always refer to the original text. */
final class PiperSegments {
    static int end(String text, int start) {
        int limit=Math.min(text.length(),start+220);
        for(int i=start;i<limit;i++) {
            char c=text.charAt(i);
            if((c=='.'||c=='!'||c=='?'||c=='\n') && i>start &&
                    (i+1==text.length()||Character.isWhitespace(text.charAt(i+1)))) return i+1;
        }
        if(limit==text.length())return limit;
        for(int i=limit;i>start;i--)if(Character.isWhitespace(text.charAt(i)))return i;
        if(Character.isHighSurrogate(text.charAt(limit-1)))limit--;
        return limit;
    }
}
