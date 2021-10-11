package io.openmessaging.writer;

import com.intel.pmem.llpl.Heap;
import com.intel.pmem.llpl.MemoryBlock;
import io.openmessaging.data.MetaData;
import io.openmessaging.data.WrappedData;
import io.openmessaging.info.PmemPageInfo;
import io.openmessaging.info.QueueInfo;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import static io.openmessaging.DefaultMessageQueueImpl.*;
import static io.openmessaging.DefaultMessageQueueImpl.PMEM_PAGE_SIZE;

public class PmemDataWriterV2 {

    public static MemoryBlock[] memoryBlocks = new MemoryBlock[PMEM_BLOCK_COUNT];
    private final BlockingQueue<PmemPageInfo>[] freePmemPageQueue = new LinkedBlockingQueue[17];
    public BlockingQueue<WrappedData> pmemWrappedDataQueue = new LinkedBlockingQueue<>();
    private final Semaphore freePageCount = new Semaphore(0);


    private void initPmem(){
        boolean initialized = Heap.exists(PMEM_ROOT + "/persistent_heap");
        Heap h = Heap.createHeap(PMEM_ROOT + "/persistent_heap", PMEM_HEAP_SIZE);

    }

    public PmemDataWriter() {
        initPmem();
        writeDataToPmem();
    }

    public void pushWrappedData(WrappedData wrappedData) {
        pmemWrappedDataQueue.offer(wrappedData);
    }

    private void writeDataToPmem() {
        for (int t = 0; t < PMEM_WRITE_THREAD_COUNT; t++) {
            new Thread(() -> {
                try {
                    WrappedData wrappedData;
                    PmemPageInfo[] pmemPageInfos;
                    ByteBuffer buf;
                    QueueInfo queueInfo;
                    MetaData meta;
                    byte[] data;
                    int requiredPageCount;
                    while (true) {
                        wrappedData = pmemWrappedDataQueue.take();
//                        long start = System.nanoTime();
                        meta = wrappedData.getMeta();
                        requiredPageCount = (meta.getDataLen() + PMEM_PAGE_SIZE - 1) / PMEM_PAGE_SIZE; // 向上取整
                        if (freePageCount.tryAcquire(requiredPageCount)) {
                            buf = wrappedData.getData();
                            data = buf.array();
                            queueInfo = meta.getQueueInfo();
                            pmemPageInfos = new PmemPageInfo[requiredPageCount];
                            for (int i = 0; i < requiredPageCount - 1; i++) {
                                pmemPageInfos[i] = freePmemPageQueue.poll();
                                memoryBlocks[pmemPageInfos[i].getBlockId()].copyFromArray(data,
                                        buf.position() + i * PMEM_PAGE_SIZE,
                                        (long)pmemPageInfos[i].getPageIndex() * PMEM_PAGE_SIZE, PMEM_PAGE_SIZE);
                            }
                            pmemPageInfos[requiredPageCount - 1] = freePmemPageQueue.poll();
                            memoryBlocks[pmemPageInfos[requiredPageCount - 1].getBlockId()].copyFromArray(data,
                                    buf.position() + (requiredPageCount - 1) * PMEM_PAGE_SIZE,
                                    (long)pmemPageInfos[requiredPageCount - 1].getPageIndex() * PMEM_PAGE_SIZE,
                                    buf.remaining() - PMEM_PAGE_SIZE * (requiredPageCount - 1));
                            queueInfo.setDataPosInPmem(meta.getOffset(), pmemPageInfos);
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

    public void offerFreePage(PmemPageInfo pmemPageInfo) {
        freePmemPageQueue.offer(pmemPageInfo);
        freePageCount.release();
    }
}