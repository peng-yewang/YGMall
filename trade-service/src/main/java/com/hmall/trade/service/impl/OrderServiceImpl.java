package com.hmall.trade.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONConfig;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.api.clients.CartClient;
import com.hmall.api.clients.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.api.dto.OrderDetailDTO;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.UserContext;
import com.hmall.trade.domain.dto.OrderFormDTO;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.po.OrderDetail;
import com.hmall.trade.domain.vo.OrderVO;
import com.hmall.trade.mapper.OrderMapper;
import com.hmall.trade.service.IOrderDetailService;
import com.hmall.trade.service.IOrderService;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmall.common.utils.RedisConstants.ORDER_ID_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final ItemClient itemClient;
    private final IOrderDetailService detailService;
    private final CartClient cartClient;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @GlobalTransactional
    public Long createOrder(OrderFormDTO orderFormDTO) {
        // 1.订单数据
        Order order = new Order();
        // 1.1.查询商品
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        // 1.2.获取商品id和数量的Map
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum));
        Set<Long> itemIds = itemNumMap.keySet();
        // 1.3.查询商品
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (items == null || items.size() < itemIds.size()) {
            throw new BadRequestException("商品不存在");
        }
        // 1.4.基于商品价格、购买数量计算商品总价：totalFee
        int total = 0;
        for (ItemDTO item : items) {
            total += item.getPrice() * itemNumMap.get(item.getId());
        }
        order.setTotalFee(total);
        // 1.5.其它属性
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(UserContext.getUser());
        order.setStatus(1);
        // 1.6.将Order写入数据库order表中
        save(order);
        // 1.7.将订单保存到redis
        String key = ORDER_ID_KEY + order.getId();
        Order cacheOrder = getById(order.getId());
        //JSONUtil.toJsonStr在转JSON时要自己先设置好日期的格式，否则后面从redis查的时候，日期会和保存的不同
        JSONConfig config = JSONConfig.create().setDateFormat("yyyy-MM-dd HH:mm:ss");
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(cacheOrder,config));
        // 2.保存订单详情
        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 3.清理购物车商品
        cartClient.deleteCartItemByIds(itemIds);

        // 4.扣减库存
        try {
            itemClient.deductStock(detailDTOS);
        } catch (Exception e) {
            throw new RuntimeException("库存不足！");
        }
        return order.getId();
    }

    @Override
    public void markOrderPaySuccess(Long orderId) {
        /*//幂等性判断：判断是否同一订单id提交多次，若是则确保和提交一次结果相同
        Order old = getById(orderId);
        if (old == null || old.getStatus() != 1) {
            //订单不存在或者订单状态不是未支付，返回
            return;
        }
        //更新订单状态为已支付
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        updateById(order);*/
        //简单操作，并判断幂等：UPDATE `order` SET status = ? , pay_time = ? WHERE id = ? AND status = 1
        // 用mybatis-plus这样写
        lambdaUpdate()
                .set(Order::getStatus,2)
                .set(Order::getPayTime, LocalDateTime.now())
                .eq(Order::getId,orderId)
                .eq(Order::getStatus,1)
                .update();
    }

    @Override
    public OrderVO queryById(Long orderId) {
        String key = ORDER_ID_KEY + orderId;
        //查缓存，无则读数据库
        String orderJson = null;
        orderJson = stringRedisTemplate.opsForValue().get(key);
        //存在，返回数据
        if (StrUtil.isNotBlank(orderJson)) {
            //命中，转为java对象并返回
            return JSONUtil.toBean(orderJson,OrderVO.class);

        }
        //不存在，查数据库
        Order order = getById(orderId);
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(order));
        //返回数据
        return BeanUtils.copyBean(order,OrderVO.class);
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }
}
