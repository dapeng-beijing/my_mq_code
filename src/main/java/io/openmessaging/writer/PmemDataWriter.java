package io.openmessaging.writer;

import com.intel.pmem.llpl.MemoryBlock;
import io.openmessaging.info.PmemPageInfo;
import io.openmessaging.info.QueueInfo;
import io.openmessaging.data.MetaData;
import io.openmessaging.data.WrappedData;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import static io.openmessaging.DefaultMessageQueueImpl.PMEM_BLOCK_COUNT;
import static io.openmessaging.DefaultMessageQueueImpl.PMEM_PAGE_SIZE;

public class PmemDataWriter {
    public static MemoryBlock[] memoryBlocks = new MemoryBlock[PMEM_BLOCK_COUNT];
    private BlockingQueue<PmemPageInfo> freePmemPageQueue = new LinkedBlockingQueue<>();
    public BlockingQueue<WrappedData> pmemWrappedDataQueue = new LinkedBlockingQueue<>();
    private final Semaphore freePageCount = new Semaphore(0);

    public PmemDataWriter() {
        writeDataToPmem();
    }

    public void pushWrappedData(WrappedData wrappedData) {
        pmemWrappedDataQueue.offer(wrappedData);
    }

    private void writeDataToPmem() {
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
                                    pmemPageInfos[i].getPageIndex() * PMEM_PAGE_SIZE, PMEM_PAGE_SIZE);
                        }
                        memoryBlocks[pmemPageInfos[requiredPageCount - 1].getBlockId()].copyFromArray(data,
                                buf.position() + (requiredPageCount - 1) * PMEM_PAGE_SIZE,
                                pmemPageInfos[requiredPageCount - 1].getPageIndex() * PMEM_PAGE_SIZE,
                                buf.remaining() - PMEM_PAGE_SIZE * (requiredPageCount - 1));
                        queueInfo.setDataPosInPmem(meta.getOffset(), pmemPageInfos);
                    }
                    meta.getCountDownLatch().countDown();
                    System.out.println("pmem处的countdown");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void offerFreePage(PmemPageInfo pmemPageInfo) {
        freePmemPageQueue.offer(pmemPageInfo);
        freePageCount.release();
    }
}
