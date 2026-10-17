package sv.vlad.lector;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.util.concurrent.*;

/** One model and one short PCM buffer. Native inference never runs on the UI thread. */
final class PiperPlayer implements AutoCloseable {
    interface Listener {
        void range(String id,int offset);
        void done(String id);
        void error(String id);
    }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    },"VladER-Piper"));
    private final Object lock=new Object();
    private OfflineTts model; // worker only, including disposal
    private String modelVoice="";
    private Job active;
    private boolean closed;
    private static final class Job {
        final String key,text,voice;
        final float rate;
        final Listener listener;
        String id;
        int offset;
        boolean paused,finished;
        AudioTrack track;
        Job(String key,String text,int offset,String voice,float rate,String id,Listener listener) {
            this.key=key;this.text=text;this.offset=offset;this.voice=voice;this.rate=rate;this.id=id;this.listener=listener;
        }
    }
    PiperPlayer(Context context){this.context=context.getApplicationContext();}
    static boolean validVoice(String voice){return "claude".equals(voice)||"davefx".equals(voice);}

    void speak(String key,String text,int offset,String voice,float rate,String id,Listener listener){
        synchronized(lock){
            if(closed)return;
            Job previous=active;
            if(previous!=null && previous.key.equals(key) && previous.text.equals(text) &&
                    previous.voice.equals(voice) && previous.rate==rate && previous.paused){
                previous.id=id;previous.paused=false;
                if(previous.track!=null)previous.track.play();
                lock.notifyAll();
                if(previous.finished)postDone(previous);
                return;
            }
            cancelLocked();
            Job job=new Job(key,text,Math.max(0,Math.min(offset,text.length())),voice,rate,id,listener);
            active=job;worker.execute(()->run(job));
        }
    }
    void pause(){synchronized(lock){if(active!=null){active.paused=true;if(active.track!=null)active.track.pause();}}}
    void cancel(){synchronized(lock){cancelLocked();}}
    private void cancelLocked(){
        if(active!=null && active.track!=null){active.track.pause();active.track.flush();active.track.release();active.track=null;}
        active=null;lock.notifyAll();
    }
    private boolean awaitPlaying(Job job)throws InterruptedException {
        synchronized(lock){while(!closed && active==job && job.paused)lock.wait();return !closed && active==job;}
    }
    private void run(Job job){
        try {
            if(!awaitPlaying(job))return;
            ensureModel(job.voice);
            while(job.offset<job.text.length()){
                if(!awaitPlaying(job))return;
                int start=job.offset,end=PiperSegments.end(job.text,start);
                String phrase=job.text.substring(start,end);
                if(phrase.trim().isEmpty()){job.offset=end;continue;}
                GeneratedAudio generated=model.generate(phrase,0,job.rate);
                if(!awaitPlaying(job))return;
                float[] samples=generated.getSamples();
                if(samples.length==0)throw new IOException("Empty Piper audio");
                short[] pcm=new short[samples.length];
                for(int i=0;i<pcm.length;i++)pcm[i]=(short)(Math.max(-1f,Math.min(1f,samples[i]))*32767);
                synchronized(lock){
                    if(closed||active!=job)return;
                    AudioTrack track=new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(generated.getSampleRate()).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length*2).build();
                    job.track=track;
                    if(track.write(pcm,0,pcm.length)!=pcm.length)throw new IOException("Incomplete audio buffer");
                    if(!job.paused)track.play();
                }
                main.post(()->{synchronized(lock){if(active==job&&!job.paused&&!closed&&job.offset==start)job.listener.range(job.id,start);}});
                while(true){
                    if(!awaitPlaying(job))return;
                    synchronized(lock){
                        if(active!=job||closed)return;
                        if(job.track.getPlaybackHeadPosition()>=pcm.length){job.track.release();job.track=null;job.offset=end;break;}
                        lock.wait(40);
                    }
                }
            }
            synchronized(lock){if(active!=job||closed)return;job.finished=true;}
            postDone(job);
        }catch(Exception|LinkageError error){
            main.post(()->{synchronized(lock){if(active==job&&!closed){String id=job.id;cancelLocked();job.listener.error(id);}}});
        }
    }
    private void postDone(Job job){main.post(()->{synchronized(lock){if(active==job&&!closed&&!job.paused)job.listener.done(job.id);}});}

    private void ensureModel(String voice)throws IOException {
        if(!validVoice(voice))throw new IOException("Unknown Piper voice");
        if(model!=null && voice.equals(modelVoice))return;
        releaseModel();
        File dir=new File(context.getNoBackupFilesDir(),"piper-v1-int8/"+voice);
        File marker=new File(dir,"ready");
        if(!marker.isFile()){
            copyAssets(context.getAssets(),"piper/"+voice,dir);
            try(FileOutputStream out=new FileOutputStream(marker)){out.write(1);}
        }
        String name="claude".equals(voice)?"es_MX-claude-high.onnx":"es_ES-davefx-medium.onnx";
        OfflineTtsVitsModelConfig vits=new OfflineTtsVitsModelConfig();
        vits.setModel(new File(dir,name).getPath());vits.setTokens(new File(dir,"tokens.txt").getPath());
        vits.setDataDir(new File(dir,"espeak-ng-data").getPath());
        OfflineTtsModelConfig models=new OfflineTtsModelConfig();models.setVits(vits);models.setNumThreads(1);models.setDebug(false);
        OfflineTtsConfig config=new OfflineTtsConfig();config.setModel(models);config.setMaxNumSentences(1);
        model=new OfflineTts(null,config);modelVoice=voice;
    }
    static void copyAssets(AssetManager assets,String path,File dest)throws IOException {
        String[] children=assets.list(path);
        if(children!=null && children.length>0){
            if(!dest.isDirectory()&&!dest.mkdirs())throw new IOException("No hay espacio para preparar Piper");
            for(String child:children)copyAssets(assets,path+"/"+child,new File(dest,child));
        }else{
            try(InputStream in=assets.open(path);OutputStream out=new FileOutputStream(dest)){
                byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
        }
    }
    void unload(){cancel();worker.execute(this::releaseModel);}
    private void releaseModel(){if(model!=null){model.release();model=null;}modelVoice="";}
    // Package-private state observations for real audio regression tests.
    boolean hasAudio(){synchronized(lock){return active!=null&&active.track!=null;}}
    int playbackFrame(){synchronized(lock){return active==null||active.track==null?-1:active.track.getPlaybackHeadPosition();}}
    @Override public void close(){synchronized(lock){closed=true;cancelLocked();}worker.execute(this::releaseModel);worker.shutdown();}
}
