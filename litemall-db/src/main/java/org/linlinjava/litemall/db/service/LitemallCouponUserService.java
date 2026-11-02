package org.linlinjava.litemall.db.service;

import com.github.pagehelper.PageHelper;
import org.linlinjava.litemall.db.dao.LitemallCouponUserMapper;
import org.linlinjava.litemall.db.domain.LitemallCouponUser;
import org.linlinjava.litemall.db.domain.LitemallCouponUserExample;
import org.linlinjava.litemall.db.util.CouponUserConstant;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class LitemallCouponUserService {
    @Resource
    private LitemallCouponUserMapper couponUserMapper;

    public Integer countCoupon(Integer couponId) {
        LitemallCouponUserExample example = new LitemallCouponUserExample();
        example.or().andCouponIdEqualTo(couponId).andDeletedEqualTo(false);
        return (int)couponUserMapper.countByExample(example);
    }

    public Integer countUserAndCoupon(Integer userId, Integer couponId) {
        LitemallCouponUserExample example = new LitemallCouponUserExample();
        example.or().andUserIdEqualTo(userId).andCouponIdEqualTo(couponId).andDeletedEqualTo(false);
        return (int)couponUserMapper.countByExample(example);
    }

    public void add(LitemallCouponUser couponUser) {
        couponUser.setAddTime(LocalDateTime.now());
        couponUser.setUpdateTime(LocalDateTime.now());
        couponUserMapper.insertSelective(couponUser);
    }

    public List<LitemallCouponUser> queryList(Integer userId, Integer couponId, Short status, Integer page, Integer size, String sort, String order) {
        LitemallCouponUserExample example = new LitemallCouponUserExample();
        LitemallCouponUserExample.Criteria criteria = example.createCriteria();
        if (userId != null) {
            criteria.andUserIdEqualTo(userId);
        }
        if(couponId != null){
            criteria.andCouponIdEqualTo(couponId);
        }
        if (status != null) {
            criteria.andStatusEqualTo(status);
        }
        criteria.andDeletedEqualTo(false);

        if (!StringUtils.isEmpty(sort) && !StringUtils.isEmpty(order)) {
            example.setOrderByClause(sort + " " + order);
        }

        if (!StringUtils.isEmpty(page) && !StringUtils.isEmpty(size)) {
            PageHelper.startPage(page, size);
        }

        return couponUserMapper.selectByExample(example);
    }

    public List<LitemallCouponUser> queryAll(Integer userId, Integer couponId) {
        return queryList(userId, couponId, CouponUserConstant.STATUS_USABLE, null, null, "add_time", "desc");
    }

    public List<LitemallCouponUser> queryAll(Integer userId) {
        return queryList(userId, null, CouponUserConstant.STATUS_USABLE, null, null, "add_time", "desc");
    }

    public LitemallCouponUser queryOne(Integer userId, Integer couponId) {
        List<LitemallCouponUser> couponUserList = queryList(userId, couponId, CouponUserConstant.STATUS_USABLE, 1, 1, "add_time", "desc");
        if(couponUserList.size() == 0){
            return null;
        }
        return couponUserList.get(0);
    }

    public LitemallCouponUser findById(Integer id) {
        return couponUserMapper.selectByPrimaryKey(id);
    }


    public int update(LitemallCouponUser couponUser) {
        couponUser.setUpdateTime(LocalDateTime.now());
        return couponUserMapper.updateByPrimaryKeySelective(couponUser);
    }

    public List<LitemallCouponUser> queryExpired() {
        LitemallCouponUserExample example = new LitemallCouponUserExample();
        example.or().andStatusEqualTo(CouponUserConstant.STATUS_USABLE).andEndTimeLessThan(LocalDateTime.now()).andDeletedEqualTo(false);
        return couponUserMapper.selectByExample(example);
    }

    public List<LitemallCouponUser> findByOid(Integer orderId) {
        LitemallCouponUserExample example = new LitemallCouponUserExample();
        example.or().andOrderIdEqualTo(orderId).andDeletedEqualTo(false);
        return couponUserMapper.selectByExample(example);
    }
    /**
     * 占用优惠券：把「校验 + 置为已使用」收敛为单条原子 SQL
     * @return 影响行数，0 表示券已被占用/不属于该用户/已删除
     */
    public int useCoupon(Integer id, Integer userId, Integer orderId) {
        LitemallCouponUser record = new LitemallCouponUser();
        record.setStatus(CouponUserConstant.STATUS_USED);
        record.setUsedTime(LocalDateTime.now());
        record.setOrderId(orderId);
        record.setUpdateTime(LocalDateTime.now());

        LitemallCouponUserExample example = new LitemallCouponUserExample();
        example.or()
                .andIdEqualTo(id)
                .andUserIdEqualTo(userId)                      // 堵越权
                .andStatusEqualTo(CouponUserConstant.STATUS_USABLE)  // 堵重复使用
                .andDeletedEqualTo(false);
        return couponUserMapper.updateByExampleSelective(record, example);
    }

}
