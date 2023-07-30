package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import com.alibaba.fastjson.JSON;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 手撸redisUtil，所有对象都用json存储
 * @author mingyifan
 * @date 2022-08-09 09:42
 */
@Slf4j
@Component
public class MzJsonRedisUtil {
    @Autowired
    public StringRedisTemplate stringRedisTemplate;

    /*----------------------------1.基础key的操作-------------------------------*/

    public void set(String key, Object value){
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(value));
    }
    /**
     *  1. 将任意的java对象序列化为json并存储在String类型的key中，并且可以设置TLL过期时间
     *  @param value 直接传整个对象即可，redis中会存json
     *  mingyifan
     *  2022/8/9 9:49
     */
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(value),time,timeUnit);
    }
    /**
     *  直接返回json
     *  mingyifan
     *  2022/8/9 14:09
     */
    public String get(String key){
        return stringRedisTemplate.opsForValue().get(key);
    }
    /**
     *
     *  @param key key全名
     *  @param T  返回类型
     *  @return T null是不存在，空串是已缓存不存在的key了
     *  mingyifan
     *  2022/8/9 10:18
     */
    public <T> T get(String key, Class<T> T){
        return JSON.parseObject(stringRedisTemplate.opsForValue().get(key), T);
    }
    public <T,Id> T get(String keyPrefix, Id id, Class<T> T){
        return this.get(keyPrefix+id,T);
    }

    /*-----------------------------2.缓存穿透的处理-------------------------------*/
    /**
     *  缓存穿透：有一个现实中就不存在的key，redis和mysql中都不存在，那么这个请求就会一直打到数据库上
     *  解决方案1（缓存不存在的key）：缓存不存在的key，使得上述情况每次都在redis处拦住，返回null
     *  根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透的问题
     *  @param keyPrefix key的前缀
     *  @param id key
     *  @param T 返回类型
     *  @param dbFailBack 查询数据库失败后的回调函数
     *  @param time  如果redis不存在，去数据库查到数据后，往redis存放的时间长度
     *  @param timeUnit 如果redis不存在，去数据库查到数据后，往redis存放的时间长度
     *  @return T null是不存在且redis中已缓存该key，其他情况都一定是对应的PO
     *  mingyifan
     *  2022/8/9 10:13
     */
    public <T,Id> T getPassThrough(String keyPrefix, Id id, Class<T> T, Function<Id,T> dbFailBack, Long time, TimeUnit timeUnit,Long unExistTime,TimeUnit unExistTimeUnit){
        String key=keyPrefix+id;
        //1. 先查redis
        String redisData = this.get(key);
        //解决缓存穿透问题.一般情况cacheObject都是null然后第一个判断问题是不是非空，他说不是，往下走，问他是不是非null，不是就去数据库查了，查过后，不管有没有都往里redis里set了值，下次查redis就一定不是null就return出去了
        if (JSON.toJSONString("").equals(redisData)){
            log.info("redis已缓存不存在的店铺"+key);
            return null;
        }
        T t = JSON.parseObject(redisData, T);
        //2. 查到就返回
        if (!ObjectUtils.isEmpty(t)){
            log.info("从redis中拿到"+key);
            return t;
        }
        //3. 查不到就再查数据库
        T dbT = dbFailBack.apply(id);
        //4. 数据库没有就缓存key
        if (ObjectUtils.isEmpty(dbT)){
            this.set(key, "",unExistTime,unExistTimeUnit);
            log.error("数据库不存在,已缓存不存在的key"+key);
            return null;
        }
        //5. 从数据库中拿到后  再写入redis为了下次查
        this.set(key,dbT,time,timeUnit);
        log.info("从sql中拿到，set进redis过了"+key);
        return dbT;
    }
    public <T,Id> T getPassThrough(String keyPrefix, Id id, Class<T> T, Function<Id,T> dbFailBack, Long time, TimeUnit timeUnit) {
        return getPassThrough(keyPrefix,id,T,dbFailBack,time,timeUnit,time,timeUnit);
    }
    /**
     *  解决方案2（布隆过滤）：
     *  @return
     *  mingyifan
     *  2022/8/9 11:14
     */
    public void get() {
    }
    /*-----------------------------3.缓存雪崩的处理-------------------------------*/



    /*-----------------------------4.缓存击穿的处理-------------------------------*/
    /**
     *  2. 将任意的java对象序列化为json并存储在String类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿的问题
     *  @param key
     *  @param value
     *  @param time
     *  @param timeUnit
     *  @return void
     *  mingyifan
     *  2022/8/9 9:49
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(redisData));
    }
//    private <T>void getWithLogicalExpire(String key, T t, Long time, TimeUnit timeUnit){
//        RedisData redisData = new RedisData();
//        redisData.setData(value);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
//        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(redisData));
//    }
    /**
     *  解决方案1————互斥锁：核心点在于这句话，setIfAbsent，如果key不存在就新增，存在的话就新增失败了
     *  Boolean flag = redisUtils.redisTemplate.opsForValue().setIfAbsent(RedisConstants.LOCK_SHOP_KEY + key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
     *  相对于逻辑过期，更注重一致性，需要一致性高的情况用这个，缺点其他用户会等待
     *  @param keyPrefix key的前缀
     *  @param id key
     *  @param T 返回类型
     *  @param dbFailBack 查询数据库失败后的回调函数
     *  @param time  如果redis不存在，去数据库查到数据后，往redis存放的时间长度
     *  @param timeUnit 如果redis不存在，去数据库查到数据后，往redis存放的时间长度
     *  @param unExistTime  保存的过期key的时间
     *  @param unExistTimeUnit  保存的过期key的时间单位
     *  @param lockTime   锁的时间
     *  @param lockTimeUnit  锁的时间单位
     *  @return T null是不存在且redis中已缓存该key，其他情况都一定是对应的PO
     *  mingyifan
     *  2022/8/9 14:11
     */
    public  <T,Id>T getWithMutex(String keyPrefix, Id id, Class<T> T, Function<Id,T> dbFailBack, Long time, TimeUnit timeUnit,Long unExistTime,TimeUnit unExistTimeUnit,String lockPrefix,Long lockTime,TimeUnit lockTimeUnit){
        //1. 先查redis
        String key=keyPrefix+id;
        String lockKey=lockPrefix+id;
        String json = this.get(key);
        //2. 查到就返回
        if (JSON.toJSONString("").equals(json)){
            log.info("redis已缓存不存在的店铺"+key);
            return null;
        }
        T t = JSON.parseObject(json, T);
        //2. 查到就返回
        if (!ObjectUtils.isEmpty(t)){
            log.info("从redis中拿到"+key);
            return t;
        }
        try {
            //第一步先获取锁
            if (tryLock(lockKey,lockTime,lockTimeUnit)){
                //拿到锁之后再次查看是否有缓存
                t = JSON.parseObject(this.get(key), T);
                if (!ObjectUtils.isEmpty(t)){
                    log.info("双重检验的时候，虽然获取锁了，但是从redis中拿到了");
                    return t;
                }
                //3. 查不到就再查数据库
                t = dbFailBack.apply(id);
                //4. 数据库没有就报错
                if (ObjectUtils.isEmpty(t)){
                    this.set(key, "",unExistTime,unExistTimeUnit);
                    log.error("数据库不存在,已缓存不存在的key"+key);
                    return null;
                }
                //5. 从数据库中拿到后  再写入redis为了下次查
                //模拟重新建立热点的key需要一秒
                Thread.sleep(10000L);
                //5. 从数据库中拿到后  再写入redis为了下次查
                this.set(key,t,time,timeUnit);
                log.info("从sql中拿到，set进redis过了"+key);
            }else {
                //没拿到就睡觉，然后递归
                Thread.sleep(RedisConstants.LOCK_SHOP_TTL*500);
                getWithMutex(keyPrefix,id,T,dbFailBack,time,timeUnit,unExistTime,unExistTimeUnit,lockPrefix,lockTime,lockTimeUnit);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
        return null;
    }
    /**
     *  记录过期key和保存的时间一致，锁最多30秒，30秒后还没有执行完，认为原来程序执行失败，强制释放锁
     *  mingyifan
     *  2022/8/9 14:10
     */
    public  <T,Id>T getWithMutex(String keyPrefix, Id id, Class<T> T, Function<Id,T> dbFailBack, Long time, TimeUnit timeUnit,String lockPrefix) {
        return getWithMutex(keyPrefix,id,T,dbFailBack,time,timeUnit,time,timeUnit,lockPrefix,30L,TimeUnit.SECONDS);
    }
        private boolean tryLock(String key,Long lockTime,TimeUnit lockTimeUnit){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", lockTime, lockTimeUnit);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 上锁加ID
     * 给锁加上一个uuid，在占锁的时候就加上uuid，删除锁的时候判断是否是自己的锁
     * @param key
     * @param lockTime
     * @param lockTimeUnit
     * @return
     */
    private boolean tryIdLock(String key,Long lockTime,TimeUnit lockTimeUnit){
        String idStr = IdUtil.getSnowflake().nextIdStr();
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, idStr, lockTime, lockTimeUnit);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 解锁加ID
     * 通过lua脚本删锁，保证删锁操作也是一个原子操作。
     * @param key
     * @return
     */
    private void unIdLock(String key){
        String idStr = stringRedisTemplate.opsForValue().get(key);
        String script = "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";
        stringRedisTemplate.execute(new DefaultRedisScript<>(script,Long.class), Collections.singletonList(key), idStr);
    }

    /**
     * Lambda 函数编程 全局锁
     * @param key
     * @param back 加锁后执行的语句
     * @param msg 加锁失败后的消息
     * @param time 时间长度
     * @param timeUnit 时间单位
     * @return
     * @param <T>
     */
    public <T> T redisLock(String key,Supplier<T> back,String msg, Long time, TimeUnit timeUnit) {
        boolean lock = tryLock(key, time, timeUnit);
        if (!lock) {
            try {
                Thread.sleep(3000);
                lock = tryLock(key, time, timeUnit);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (lock) {
            try {
                return back.get();
            }catch (Exception e){
                log.error("全局锁运行错误", e);
            }
            finally {
                unLock(key);
            }
        }
        throw new RuntimeException(msg);
    }

    /**
     * Lambda 函数编程 全局锁 加ID
     * @param key
     * @param back 加锁后执行的语句
     * @param msg 加锁失败后的消息
     * @param timeoutBack 获取自选等待时间 ()-> TimeUnit.SECONDS.toMillis(3)
     * @param time 时间长度
     * @param timeUnit 时间单位
     * @return
     * @param <T>
     */
    public <T> T redisIdLock(String key,Supplier<T> back,String msg, Supplier<Long> timeoutBack,Long time, TimeUnit timeUnit) {
        boolean lock = tryIdLock(key, time, timeUnit);
        if (!lock) {
            long start = System.currentTimeMillis();
            Long timeoutMillis = timeoutBack.get();
            while ((System.currentTimeMillis() - start) < timeoutMillis && !lock){
                // 自旋等待，并防止出现死锁
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                lock = tryIdLock(key, time, timeUnit);
            }
        }
        if (lock) {
            try {
                return back.get();
            }catch (Exception e){
                log.error("全局锁运行错误", e);
            }
            finally {
                unIdLock(key);
            }
        }
        throw new RuntimeException(msg);
    }

    private ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(5);
    /**
     *  解决方案2————逻辑过期
     *  更侧重于可用性，用户不会等待，但是可能拿到旧的数据
     *  @param keyPrefix key的前缀
     *  @param id key
     *  @param T 返回类型
     *  @param dbFailBack 查询数据库失败后的回调函数
     *  @param time  如果redis中已过期，去数据库查到数据后，往redis存放的时间长度
     *  @param timeUnit 如果redis中已过期，去数据库查到数据后，往redis存放的时间长度
     *  @param lockTime  锁的时间
     *  @param lockTimeUnit  锁的时间单位
     *  @return T
     *  mingyifan
     *  2022/8/9 14:29
     */
    public <T,Id>T getWithLogicalExpire(String keyPrefix, Id id, Class<T> T, Function<Id,T> dbFailBack, Long time, TimeUnit timeUnit,String lockPrefix,Long lockTime,TimeUnit lockTimeUnit){
        //1. 先查redis
        String key=keyPrefix+id;
        String lockKey=lockPrefix+id;
        String s = this.get(key);
        //2. 处理查不到的情况，业务中，这个是一定会查到的，因为是手动set的热点key
        if (ObjectUtils.isEmpty(s)){
           log.info("查不到key");
            return null;
        }
        RedisData redisData = JSON.parseObject(s,RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //2. 处理查不到的情况，业务中，这个是一定会查到的，因为是手动set的热点key
        if (expireTime.compareTo(LocalDateTime.now()) < 0){
            try {
                //过期了就尝试获取锁
                if (tryLock(lockKey,lockTime,lockTimeUnit)){
                    //双重检测
                    redisData = JSON.parseObject(get(key),RedisData.class);
                    expireTime = redisData.getExpireTime();
                    if (expireTime.compareTo(LocalDateTime.now())>= 0){
                        log.info("被双重检测捕获到，直接退出");
                        unLock(lockKey);
                        return  (T) redisData.getData();
                    }
                    //依旧过期才更新，获取到了就去更新热点key
                    log.info("获取到锁，开始更新热点key");
                    CACHE_REBUILD_EXECUTOR.submit(()->{
                        updateHotKey(keyPrefix,id,dbFailBack,time,timeUnit);
                        unLock(lockKey);
                    });
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        log.info("返回的日期:"+redisData.getExpireTime());
        return beanCopy(redisData.getData(),T);
    }
    private <T>T beanCopy(Object o,Class<T> t){
        try {
            T res = t.newInstance();
            BeanUtils.copyProperties(o,res);
            return res;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public  <T,Id>T getWithLogicalExpire(String keyPrefix, Id id, Class<T> T, Function<Id,T> dbFailBack,String lockPrefix, Long time, TimeUnit timeUnit) {
        return getWithLogicalExpire(keyPrefix,id,T,dbFailBack,time,timeUnit,lockPrefix,30L,TimeUnit.SECONDS);
    }
    /**
     *  更新热点key
     *  mingyifan
     *  2022/8/8 15:51
     */
    private  <Id,T>void updateHotKey(String keyPrefix, Id id,Function<Id,T> dbFailBack,Long expireSeconds,TimeUnit expireTimeUnit){
        T t = dbFailBack.apply(id);
        setWithLogicalExpire(keyPrefix+id,t,expireSeconds,expireTimeUnit);
    }

    @Data
    static class RedisData implements Serializable {
        private LocalDateTime expireTime;
        private Object data;
    }
}
