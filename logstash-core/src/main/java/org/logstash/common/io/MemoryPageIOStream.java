package org.logstash.common.io;

import org.logstash.ackedqueue.Checkpoint;
import org.logstash.ackedqueue.Queueable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MemoryPageIOStream implements PageIO {
    static final int CHECKSUM_SIZE = Integer.BYTES;
    static final int LENGTH_SIZE = Integer.BYTES;
    static final int SEQNUM_SIZE = Long.BYTES;
    static final int MIN_RECORD_SIZE = SEQNUM_SIZE + LENGTH_SIZE + CHECKSUM_SIZE;
    static final int VERSION_SIZE = Short.BYTES;

    private final byte[] buffer;
    private final int capacity;
    private int writePosition;
    private int readPosition;
    private int elementCount;
    private long minSeqNum;
    private ByteBufferStreamInput streamedInput;
    private ByteArrayStreamOutput streamedOutput;
    private BufferedChecksumStreamOutput crcWrappedOutput;
    private final List<Integer> offsetMap;
    private String dirPath = "";
    private String headerDetails = "";

    public static int persistedByteCount(byte[] data) {
        return persistedByteCount(data.length);
    }

    public static int persistedByteCount(int length) {
        return MIN_RECORD_SIZE + length;
    }

    public MemoryPageIOStream(int capacity, String dirPath) throws IOException {
        this(capacity, new byte[capacity]);
        this.dirPath = dirPath;
    }

    public MemoryPageIOStream(int capacity) throws IOException {
        this(capacity, new byte[capacity]);
    }

    public MemoryPageIOStream(int capacity, byte[] initialBytes) throws IOException {
        this.capacity = capacity;
        if (initialBytes.length > capacity) {
            throw new IOException("initial bytes greater than capacity");
        }
        buffer = initialBytes;
        offsetMap = new ArrayList<>();
        streamedInput = new ByteBufferStreamInput(ByteBuffer.wrap(buffer));
        streamedOutput = new ByteArrayStreamOutput(buffer);
        crcWrappedOutput = new BufferedChecksumStreamOutput(streamedOutput);
    }

    @Override
    public void open(long minSeqNum, int elementCount) throws IOException {
        this.minSeqNum = minSeqNum;
        this.elementCount = elementCount;
        writePosition = verifyHeader();
        readPosition = writePosition;
        if (elementCount > 0) {
            long seqNumRead;
            BufferedChecksumStreamInput in = new BufferedChecksumStreamInput(streamedInput);
            for (int i = 0; i < this.elementCount; i++) {
                if (writePosition + SEQNUM_SIZE + LENGTH_SIZE > capacity) {
                    throw new IOException(String.format("cannot read seqNum and length bytes past buffer capacity"));
                }

                seqNumRead = in.readLong();

                //verify that the buffer starts with the min sequence number
                if (i == 0 && seqNumRead != this.minSeqNum) {
                    String msg = String.format("Page minSeqNum mismatch, expected: %d, actual: %d", this.minSeqNum, seqNumRead);
                    throw new IOException(msg);
                }

                in.resetDigest();
                byte[] bytes = in.readByteArray();
                int actualChecksum = (int) in.getChecksum();
                int expectedChecksum = in.readInt();

                if (actualChecksum != expectedChecksum) {
                    // explode with tragic error
                }

                offsetMap.add(writePosition);
                writePosition += persistedByteCount(bytes);
            }
            setReadPoint(this.minSeqNum);
        }
    }

    @Override
    public void create() throws IOException {
        writePosition = addHeader();
        readPosition = writePosition;
        this.minSeqNum = 1L;
        this.elementCount = 0;
    }

    @Override
    public boolean hasSpace(int byteSize) {
        return this.capacity >= writePosition + persistedByteCount(byteSize);
    }

    @Override
    public void write(byte[] bytes, Queueable element) throws IOException {
        long seqNum = element.getSeqNum();
        write(bytes, seqNum);
    }

    @Override
    public List<ReadElementValue> read(long seqNum, int limit) throws IOException {
        if (elementCount == 0) {
            return new ArrayList<>();
        }
        setReadPoint(seqNum);
        return read(limit);
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public void deactivate() {
        // do nothing
    }

    @Override
    public void activate() {
        // do nothing
    }

    @Override
    public void ensurePersisted() {
        // do nothing
    }

    @Override
    public void purge() throws IOException {
        // do nothing
    }

    //@Override
    public void setPageHeaderDetails(String details) {
        headerDetails = details;
    }

    public int getWritePosition() {
        return writePosition;
    }

    public int getElementCount() {
        return elementCount;
    }

    public long getMinSeqNum() {
        return minSeqNum;
    }

    // used in tests
    public byte[] getBuffer() {
        return buffer;
    }

    // used in tests
    public String readHeaderDetails() throws IOException {
        int tempPosition = readPosition;
        streamedInput.movePosition(0);
        int ver = (int) streamedInput.readShort();
        String details = new String(streamedInput.readByteArray());
        streamedInput.movePosition(tempPosition);
        return details;
    }

    // Optional write method, remove if only used in tests
    public void write(Queueable element) throws IOException {
        byte[] bytes = element.serialize();
        long seqNum = element.getSeqNum();
        write(bytes, seqNum);
    }

    // Optional write method, remove if only used in tests
    public void write(byte[] bytes, long seqNum) throws IOException {
        int pos = this.writePosition;
        int writeLength = persistedByteCount(bytes);
        writeToBuffer(seqNum, bytes, writeLength);
        writePosition += writeLength;
        assert writePosition == streamedOutput.getPosition() :
                String.format("writePosition=%d != streamedOutput position=%d", writePosition, streamedOutput.getPosition());
        if (elementCount <= 0) {
            this.minSeqNum = seqNum;
        }
        this.offsetMap.add(pos);
        elementCount++;
    }

    private void setReadPoint(long seqNum) throws IOException {
        int readPosition = offsetMap.get(calcRelativeSeqNum(seqNum));
        streamedInput.movePosition(readPosition);
    }

    private int calcRelativeSeqNum(long seqNum) {
        return (int) (seqNum - minSeqNum);
    }

    private int addHeader() throws IOException {
        streamedOutput.writeShort((short) Checkpoint.VERSION);
        byte[] details = headerDetails.getBytes();
        streamedOutput.writeByteArray(details);
        return VERSION_SIZE + LENGTH_SIZE + details.length;
    }

    private int verifyHeader() throws IOException {
        int ver = (int) streamedInput.readShort();
        if (ver != Checkpoint.VERSION) {
            String msg = String.format("Page version mismatch, expecting: %d, this version: %d", Checkpoint.VERSION, ver);
            throw new IOException(msg);
        }
        int len = streamedInput.readInt();
        streamedInput.skip(len);
        return VERSION_SIZE + LENGTH_SIZE + len;
    }

    private void writeToBuffer(long seqNum, byte[] data, int len) throws IOException {
        streamedOutput.setWriteWindow(writePosition, len);
        crcWrappedOutput.writeLong(seqNum);
        crcWrappedOutput.resetDigest();
        crcWrappedOutput.writeByteArray(data);
        long checksum = crcWrappedOutput.getChecksum();
        crcWrappedOutput.writeInt((int) checksum);
        crcWrappedOutput.flush();
        crcWrappedOutput.close();
    }

    private List<ReadElementValue> read(int limit) throws IOException {
        ArrayList<ReadElementValue> result = new ArrayList<>();
        int upto = available(limit);
        for (int i = 0; i < upto; i++) {
            long seqnum = readSeqNum();
            byte[] data = readData();
            skipChecksum();
            result.add(new ReadElementValue(seqnum, data));
        }
        return result;
    }

    private long readSeqNum() throws IOException {
        return streamedInput.readLong();
    }

    private byte[] readData() throws IOException {
        return streamedInput.readByteArray();
    }

    private void skipChecksum() throws IOException {
        streamedInput.skip(CHECKSUM_SIZE);
    }

    private int available(int sought) {
        if (elementCount < 1) return 0;
        if (elementCount < sought) return elementCount;
        return sought;
    }
}