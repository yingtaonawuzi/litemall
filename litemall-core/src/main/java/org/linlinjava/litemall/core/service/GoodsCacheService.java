package org.linlinjava.litemall.core.service;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.linlinjava.litemall.db.domain.LitemallGoods;
import org.linlinjava.litemall.db.service.LitemallGoodsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class GoodsCacheService {

    private static final Log log = LogFactory.getLog(GoodsCacheService.class);

    private static final String KEY_PREFIX = "litemall:goods:";
    /**
     * 空值标记的 key 前缀。
     * 数据库查不到时写一个短 TTL 的标记，后续请求看到标记就直接返回 null，不再打数据库（防穿透）。
     */
    private static final String NULL_KEY_PREFIX = "litemall:goods:null:";
    private static final long TTL_MINUTES = 30;
    /** 空值标记必须远短于正常缓存：商品可能是之后才上架的 */
    private static final long NULL_TTL_SECONDS = 60;
    /** 延迟双删的等待时间，从「事务提交后」开始计算 */
    private static final long DOUBLE_DELETE_DELAY_MILLIS = 500;

    /** 分段锁：按 id 取模，不同商品落在不同锁上，避免互相阻塞 */
    private final Object[] rebuildLocks = new Object[64];

    private final ScheduledExecutorService delayExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "goods-cache-delay-evict");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private LitemallGoodsService goodsService;

    public GoodsCacheService() {
        for (int i = 0; i < rebuildLocks.length; i++) {
            rebuildLocks[i] = new Object();
        }
    }

    public LitemallGoods findById(Integer id) {
        String key = KEY_PREFIX + id;

        // 1. 命中缓存直接返回
        LitemallGoods cached = readCache(key);
        if (cached != null) {
            return cached;
        }

        // 2. 命中空值标记：数据库里确实没有，直接返回，不再回源
        if (isNullMarked(id)) {
            return null;
        }

        // 3. 未命中，加锁重建：同一个 id 同时只允许一个线程回源（防击穿）
        synchronized (rebuildLocks[Math.floorMod(id, rebuildLocks.length)]) {
            // 3.1 双重检查：等锁期间可能已经有别的线程回填了
            cached = readCache(key);
            if (cached != null) {
                return cached;
            }
            if (isNullMarked(id)) {
                return null;
            }

            // 3.2 回源查库
            LitemallGoods goods = goodsService.findById(id);

            // 3.3 查不到就缓存空值，短 TTL 挡住穿透
            if (goods == null) {
                stringRedisTemplate.opsForValue()
                        .set(NULL_KEY_PREFIX + id, "1", NULL_TTL_SECONDS, TimeUnit.SECONDS);
                return null;
            }

            redisTemplate.opsForValue().set(key, goods, TTL_MINUTES, TimeUnit.MINUTES);
            return goods;
        }
    }

    private LitemallGoods readCache(String key) {
        Object cached = redisTemplate.opsForValue().get(key);
        return cached instanceof LitemallGoods ? (LitemallGoods) cached : null;
    }

    private boolean isNullMarked(Integer id) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(NULL_KEY_PREFIX + id));
    }

    public void evict(Integer id) {
        redisTemplate.delete(KEY_PREFIX + id);
        stringRedisTemplate.delete(NULL_KEY_PREFIX + id);
    }

    /**
     * 延迟双删：
     * 1) 立刻删一次，尽量缩短脏窗口；
     * 2) 在「事务提交之后」再延迟删一次，删掉提交前被并发读回填的旧值。
     * 第二次删除必须挂在事务提交后，否则 update 本身耗时超过延迟时间时，第二次删除会白删。
     */
    public void evictWithDelay(final Integer id) {
        evict(id);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    scheduleEvict(id);
                }
            });
        } else {
            scheduleEvict(id);
        }
    }

    private void scheduleEvict(final Integer id) {
        delayExecutor.schedule(() -> {
            try {
                evict(id);
            } catch (Exception e) {
                log.error("延迟删除商品缓存失败 id=" + id, e);
            }
        }, DOUBLE_DELETE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        delayExecutor.shutdown();
    }
}
