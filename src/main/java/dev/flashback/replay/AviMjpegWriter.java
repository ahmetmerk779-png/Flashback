package dev.flashback.replay;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Harici program gerektirmeyen minimal AVI (MJPEG) yazıcısı. Sabit kare hızı, tek video akışı, ses yok.
 * Telefon/Pojav gibi ffmpeg bulunmayan ortamlar için. (4 GB sınırı vardır.)
 */
public final class AviMjpegWriter implements Closeable {
    private final RandomAccessFile f;
    private final int w, h, fps;
    private long moviSizePos, moviIdPos, avihFramesPos, strhLenPos;
    private final List<int[]> index = new ArrayList<>();
    private int frames;

    public AviMjpegWriter(Path path, int w, int h, int fps) throws IOException {
        this.w = w; this.h = h; this.fps = fps;
        f = new RandomAccessFile(path.toFile(), "rw");
        f.setLength(0);
        writeHeader();
    }

    private ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }
    private void fourcc(String s) throws IOException { f.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII)); }
    private void u32(int v) throws IOException { f.write(le(4).putInt(v).array()); }
    private void u16(int v) throws IOException { f.write(le(2).putShort((short) v).array()); }

    private void writeHeader() throws IOException {
        fourcc("RIFF"); u32(0); fourcc("AVI ");
        fourcc("LIST"); u32(192); fourcc("hdrl");
        fourcc("avih"); u32(56);
        u32(1_000_000 / fps); u32(0); u32(0); u32(0x10);
        avihFramesPos = f.getFilePointer(); u32(0);   // toplam kare (sonda yazılır)
        u32(0); u32(1); u32(0); u32(w); u32(h);
        u32(0); u32(0); u32(0); u32(0);
        fourcc("LIST"); u32(116); fourcc("strl");
        fourcc("strh"); u32(56);
        fourcc("vids"); fourcc("MJPG"); u32(0); u16(0); u16(0); u32(0);
        u32(1); u32(fps); u32(0);
        strhLenPos = f.getFilePointer(); u32(0);      // uzunluk (sonda yazılır)
        u32(0); u32(-1); u32(0);
        u16(0); u16(0); u16(w); u16(h);
        fourcc("strf"); u32(40);
        u32(40); u32(w); u32(h); u16(1); u16(24); fourcc("MJPG"); u32(w * h * 3);
        u32(0); u32(0); u32(0); u32(0);
        fourcc("LIST"); moviSizePos = f.getFilePointer(); u32(0);
        moviIdPos = f.getFilePointer(); fourcc("movi");
    }

    public void writeFrame(byte[] jpeg) throws IOException {
        long pos = f.getFilePointer();
        fourcc("00dc");
        u32(jpeg.length);
        f.write(jpeg);
        if ((jpeg.length & 1) == 1) f.write(0);
        index.add(new int[]{(int) (pos - moviIdPos), jpeg.length});
        frames++;
    }

    @Override
    public void close() throws IOException {
        long end = f.getFilePointer();
        f.seek(moviSizePos); u32((int) (end - moviSizePos - 4));
        f.seek(end);
        fourcc("idx1"); u32(index.size() * 16);
        ByteBuffer b = le(16 * index.size());
        for (int[] e : index) { b.put("00dc".getBytes()); b.putInt(0x10); b.putInt(e[0]); b.putInt(e[1]); }
        f.write(b.array());
        long total = f.getFilePointer();
        f.seek(4); u32((int) (total - 8));
        f.seek(avihFramesPos); u32(frames);
        f.seek(strhLenPos); u32(frames);
        f.close();
    }
}
