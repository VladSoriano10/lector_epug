package sv.vlad.lector;

import java.util.List;

/** Text chunk IDs stay unchanged, so existing EPUB bookmarks remain valid. */
public final class Narration {
    public static final class Item {
        public final int chunk;
        public final String locator;
        private Item(int chunk,String locator){this.chunk=chunk;this.locator=locator;}
        public boolean image(){return chunk<0;}
        public static Item image(String locator){return new Item(-1,locator);}
        public static Item text(int chunk,String locator){return new Item(chunk,locator);}
    }
    public static int find(List<Item> items,String locator,int chunk) {
        for(int i=0;i<items.size();i++)if(items.get(i).locator.equals(locator))return i;
        if(locator==null || locator.isEmpty())return 0;
        for(int i=0;i<items.size();i++)if(items.get(i).chunk==chunk)return i;
        return 0;
    }
    /** A paused image delay resumes its remaining duration, never calls the next text early. */
    public static final class Delay {
        private long remaining,started;
        private boolean running;
        public void set(long millis){remaining=Math.max(0,millis);running=false;}
        public long start(long now){started=now;running=true;return remaining;}
        public void pause(long now){if(running)remaining=Math.max(0,remaining-(now-started));running=false;}
        public long remaining(){return remaining;}
    }
}
