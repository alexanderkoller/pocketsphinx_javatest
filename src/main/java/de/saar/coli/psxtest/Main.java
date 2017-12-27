/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.saar.coli.psxtest;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import edu.cmu.pocketsphinx.Decoder;
import edu.cmu.pocketsphinx.Config;
import edu.cmu.pocketsphinx.Segment;
import edu.cmu.pocketsphinx.Hypothesis;
import edu.cmu.pocketsphinx.SegmentIterator;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 *
 * @author koller
 */
public class Main {

    static {
        System.loadLibrary("pocketsphinx_jni");
    }

    // from Pocketsphinx Python example:
    // stream = p.open(format=pyaudio.paInt16, channels=1, rate=16000, input=True, frames_per_buffer=1024)
    private static AudioFormat getAudioFormat() {
        float sampleRate = 16000.0F;
        int sampleSizeBits = 16;
        int channels = 1;
        boolean signed = true;
        boolean bigEndian = false;

        return new AudioFormat(sampleRate, sampleSizeBits, channels, signed, bigEndian);
    }

    private static interface PocketsphinxDataSource {

        public void start();

        public void stop();

        public int read(byte[] buffer) throws IOException;
    }

    private static class InputStreamDataSource implements PocketsphinxDataSource {

        private InputStream ais;

        public InputStreamDataSource(InputStream is) throws FileNotFoundException {
            ais = is;
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            return ais.read(buffer);
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    }

    private static class MicrophoneDataSource implements PocketsphinxDataSource {

        private TargetDataLine line;

        public MicrophoneDataSource(AudioFormat format) throws LineUnavailableException {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            System.err.println(info);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.start();
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            return line.read(buffer, 0, buffer.length);
        }

        @Override
        public void start() {
            line.start();
        }

        @Override
        public void stop() {
        }

        public InputStream getInputStream() {
            return new AudioInputStream(line);
        }
    }

    private static class MutableBoolean {

        public boolean val;
    }

    public static void main(String args[]) throws IOException, LineUnavailableException {
        MutableBoolean stopped = new MutableBoolean();
        Controller controller = new Controller((x) -> {
            stopped.val = true;
        });

        new Thread() {
            @Override
            public void run() {
                controller.setVisible(true);
            }
        }.start();

        Config c = Decoder.defaultConfig();
        c.setString("-hmm", "model/en-us/en-us");
        c.setString("-lm", "model/en-us/en-us.lm.bin");
        c.setString("-dict", "model/en-us/cmudict-en-us.dict");
        Decoder d = new Decoder(c);

//        PocketsphinxDataSource ds = new FileDataSource(new File("goforward.raw"));
//        MicrophoneDataSource mds = new MicrophoneDataSource(getAudioFormat());
//        mds.start();
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, getAudioFormat());
        System.err.println(info);
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(getAudioFormat());
        line.start();
        PocketsphinxDataSource ds = new InputStreamDataSource(new AudioInputStream(line));

        System.err.println("***** START LINE *****");

        d.startUtt();
        d.setRawdataSize(300000);
        byte[] b = new byte[4096];
        int nbytes;

        boolean isInSpeech = false;
        long sample = 0;

        while ((nbytes = ds.read(b)) >= 0) {
            System.err.printf("Sample %d -> %d bytes\n", sample++, nbytes);
            ByteBuffer bb = ByteBuffer.wrap(b, 0, nbytes);
            bb.order(ByteOrder.LITTLE_ENDIAN);
            short[] s = new short[nbytes / 2];
            bb.asShortBuffer().get(s);
            d.processRaw(s, nbytes / 2, false, false);
            
            int volumeLevel = getLevel(s);
            System.err.println(volumeLevel);
            controller.setAudioLevel(volumeLevel/50);

            if (stopped.val) {
                System.err.println("***** SPEECH STOPPED *****");
                break;
            }
        }

        d.endUtt();

        short[] data = d.getRawdata();
        System.out.println("Data size: " + data.length);
        writeToFile(data);

        System.out.println(d.hyp().getHypstr());

        for (Segment seg : d.seg()) {
            System.out.println(seg.getWord());
        }
        
        System.exit(0);
    }
    
    // Returns arithmetic mean of data values.
    private static int getLevel(short[] data) {
        long sum = 0;
        for( int i = 0; i < data.length; i++ ) {
            sum += Math.abs(data[i]);
        }
        return (int) (sum/data.length);
    }

    private static void writeToFile(short[] data) throws IOException {
        /*
        System.out.println("Data size: " + data.length);
        DataOutputStream dos = new DataOutputStream(new FileOutputStream(new File("/tmp/test.raw")));
        for (int i = 0; i < data.length; i++) {
            dos.writeShort(data[i]);
        }
        dos.close();
         */

        ByteBuffer byteBuf = ByteBuffer.allocate(2 * data.length);
        for( int i = 0; i < data.length; i++ ) {
            byteBuf.putShort(data[i]);
        }
        
        byte[] bbuf = byteBuf.array();
        AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(bbuf), getAudioFormat(), bbuf.length);
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File("/tmp/test.wav"));
    }

}
