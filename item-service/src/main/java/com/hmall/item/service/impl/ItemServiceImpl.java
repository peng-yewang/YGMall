package com.hmall.item.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.item.domain.po.Item;
import com.hmall.item.mapper.ItemMapper;
import com.hmall.item.service.IItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.hmall.common.utils.RedisConstants.ITEM_ID;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author 虎哥
 */
@Service
@RequiredArgsConstructor
public class ItemServiceImpl extends ServiceImpl<ItemMapper, Item> implements IItemService {
    private final StringRedisTemplate stringRedisTemplate;
    @Override
    @Transactional
    public void deductStock(List<OrderDetailDTO> items) {
        String sqlStatement = "com.hmall.item.mapper.ItemMapper.updateStock";
        boolean r = false;
        try {
            r = executeBatch(items, (sqlSession, entity) -> sqlSession.update(sqlStatement, entity));
        } catch (Exception e) {
            log.error("更新库存异常", e);
            throw new BizIllegalException("库存不足！");
        }
        if (!r) {
            throw new BizIllegalException("库存不足！");
        }
    }

    @Override
    public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
        String key = ITEM_ID;
        List<ItemDTO> items = new ArrayList<>();
        //select * from item where id=?.
        //查缓存
        for (Long id:ids) {
            key = ITEM_ID+id;
            String itemCache = stringRedisTemplate.opsForValue().get(key);
            if(itemCache!=null){
                items.add(JSONUtil.toBean(itemCache,ItemDTO.class));
            }
        }
        Integer idSize = ids.size();
        //缓存与数据库一致性判断
        if (idSize.equals(items.size())){
            //一致直接返回
            return items;
        }
        //查库
        items= BeanUtils.copyList(listByIds(ids), ItemDTO.class);
        //写入缓存
        for (ItemDTO item:items){
            key = ITEM_ID+item.getId();
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(item));
        }
        //返回
        return items;
    }
}
