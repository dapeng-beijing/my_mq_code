package io.openmessaging.writer;

import com.intel.pmem.llpl.Heap;
import com.intel.pmem.llpl.MemoryBlock;
import io.openmessaging.data.MetaData;
import io.openmessaging.data.WrappedData;
import io.openmessaging.info.PmemPageInfo;
import io.openmessaging.info.QueueInfo;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

import static io.openmessaging.DefaultMessageQueueImpl.*;

public class PmemDataWriterV2 {

//    public static MemoryBlock[] memoryBlocks = new MemoryBlock[PMEM_BLOCK_COUNT];
    public static final BlockingQueue<PmemPageInfo>[] freePmemPageQueues = new LinkedBlockingQueue[PMEM_BLOCK_GROUP_COUNT];
    public BlockingQueue<WrappedData> pmemWrappedDataQueue = new LinkedBlockingQueue<>();
//    private final Semaphore freePageCount = new Semaphore(0);


    private void initPmem(){

        for (int i = 0; i < PMEM_BLOCK_GROUP_COUNT; i++) {
            freePmemPageQueues[i] = new LinkedBlockingQueue<>();
        }
        boolean initialized = Heap.exists(PMEM_ROOT + "/persistent_heap");
        Heap h;
        try {
            h = initialized ? Heap.openHeap(PMEM_ROOT + "/persistent_heap") : Heap.createHeap(PMEM_ROOT + "/persistent_heap", PMEM_HEAP_SIZE);
        }catch (Exception e) {
            return;
        }
        int nThread = 8;

        CountDownLatch countDownLatch = new CountDownLatch(nThread);
        for (int threadId = 0; threadId < nThread; threadId++) {
            final int heapId = threadId;
            new Thread(() -> {
                long start = System.currentTimeMillis();
                try {
                    while (true){
                        for (int i = 1; i <= PMEM_BLOCK_GROUP_COUNT; i++) {
                            MemoryBlock memoryBlock = h.allocateMemoryBlock(i * 1024);
                            PmemPageInfo pmemPageInfo = new PmemPageInfo();
                            pmemPageInfo.block = memoryBlock;
                            pmemPageInfo.freePmemPageQueueIndex = i - 1;
                            freePmemPageQueues[i - 1].offer(pmemPageInfo);
                            if (i == 17){
                                int i1 = 1;
                            }
                        }
                    }
                }catch (Exception e){
                    countDownLatch.countDown();
                    System.out.printf("heap is full.(%d)\n", System.currentTimeMillis() - start);
                }
            }).start();
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public PmemDataWriterV2() {
        initPmem();
        writeDataToPmem();
    }

    public void pushWrappedData(WrappedData wrappedData) {
        pmemWrappedDataQueue.offer(wrappedData);
    }

    public static int getFreePmemPageQueueIndex(short dataLen){
        return (dataLen + 1023) / 1024 - 1;
    }

    private void writeDataToPmem() {
        for (int t = 0; t < PMEM_WRITE_THREAD_COUNT; t++) {
            new Thread(() -> {
                try {
                    WrappedData wrappedData;
                    ByteBuffer buf;
                    QueueInfo queueInfo;
                    MetaData meta;
                    byte[] data;
                    MemoryBlock memoryBlock;
                    PmemPageInfo pmemPageInfo;
//                    int requiredPageCount;
                    while (true) {
                        wrappedData = pmemWrappedDataQueue.take();
//                        long start = System.nanoTime();
                        meta = wrappedData.getMeta();
//                        requiredPageCount = (meta.getDataLen() + PMEM_PAGE_SIZE - 1) / PMEM_PAGE_SIZE; // 向上取整


                        if ((pmemPageInfo = freePmemPageQueues[getFreePmemPageQueueIndex(meta.getDataLen())].poll()) != null) {
                            memoryBlock = pmemPageInfo.block;
                            buf = wrappedData.getData();
                            data = buf.array();
                            queueInfo = meta.getQueueInfo();

                            memoryBlock.copyFromArray(data, buf.position(), 0, buf.remaining());

//                            queueInfo.setDataPosInPmem(meta.getOffset(), pmemPageInfos);

                            queueInfo.setPmemBlockMemory(meta.getOffset(), pmemPageInfo);


//                            long end = System.nanoTime();
//                            System.out.printf("pmem耗时：%d", end -start);
                        }
                        meta.getCountDownLatch().countDown();

                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

//    public void offerFreePage(PmemPageInfo pmemPageInfo) {
//        freePmemPageQueues.offer(pmemPageInfo);
//    }
}
