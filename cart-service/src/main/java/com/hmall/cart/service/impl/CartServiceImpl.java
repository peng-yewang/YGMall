package com.hmall.cart.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.api.clients.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.cart.config.CartProperties;
import com.hmall.cart.domain.dto.CartFormDTO;
import com.hmall.cart.domain.po.Cart;
import com.hmall.cart.domain.vo.CartVO;
import com.hmall.cart.mapper.CartMapper;
import com.hmall.cart.service.ICartService;
import com.hmall.common.exception.BizIllegalException;
import com.hmall.common.utils.BeanUtils;
import com.hmall.common.utils.CollUtils;
import com.hmall.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmall.common.utils.RedisConstants.CART_ID_KEY;
import static com.hmall.common.utils.RedisConstants.SHORT_SIXTY_MINUTES;

/**
 * <p>
 * 订单详情表 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements ICartService {

    private final RestTemplate restTemplate;

    private  final DiscoveryClient discoveryClient;

    private final CartProperties cartProperties;

    private final ItemClient itemClient;

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void addItem2Cart(CartFormDTO cartFormDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();

        // 2.判断是否已经存在
        if(checkItemExists(cartFormDTO.getItemId(), userId)){
            // 2.1.存在，则更新数量
            baseMapper.updateNum(cartFormDTO.getItemId(), userId);
            return;
        }
        // 2.2.不存在，判断是否超过购物车数量
        checkCartsFull(userId);
        // 3.新增购物车条目
        // 3.1.转换PO
        Cart cart = BeanUtils.copyBean(cartFormDTO, Cart.class);
        // 3.2.保存当前用户
        cart.setUserId(userId);
        // 3.3.保存到数据库
        save(cart);
    }

    @Override
    public List<CartVO> queryMyCarts() {
        // 1.查询我的购物车列表
        //查缓存
        List<String> cartList = stringRedisTemplate.opsForList().range(CART_ID_KEY, 0, -1);
        //不为空则返回
        ArrayList<CartVO> cartVOS = new ArrayList<>();
        if (cartList.size()!=0) {
            //转为PayOrderVO并返回
            for(String payOrderList:cartList){
                cartVOS.add(JSONUtil.toBean(payOrderList,CartVO.class));
            }
            // 3.处理VO中的商品信息
            handleCartItems(cartVOS);
            return cartVOS;
        }
        //缓存没有，查数据库
        List<Cart> carts = lambdaQuery().eq(Cart::getUserId,  UserContext.getUser()).list();
        if (CollUtils.isEmpty(carts)) {
            //为空返回
            return CollUtils.emptyList();
        }
        //存入缓存
        for(Cart cart:carts){
            CartVO cartVO = BeanUtils.copyBean(cart, CartVO.class);
            cartVOS.add(cartVO);
            //转为json存入redis
            stringRedisTemplate.opsForList().rightPush(CART_ID_KEY, JSONUtil.toJsonStr(cartVO));
        }
        //设置redis有效期
        stringRedisTemplate.expire(CART_ID_KEY,SHORT_SIXTY_MINUTES, TimeUnit.MINUTES);
        // 3.处理VO中的商品信息
        handleCartItems(cartVOS);
        // 4.返回
        return cartVOS;
    }

    private void handleCartItems(List<CartVO> vos) {
        // TODO 1.获取商品id
        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());
        /*// 2.查询商品
        //List<ItemDTO> items = itemService.queryItemByIds(itemIds);
        //2.1.根据服务名称获取服务的实例列表
        List<ServiceInstance> instances = discoveryClient.getInstances("item-service");
        if(CollUtils.isEmpty(instances)){
            return;
        }
        //2.2.手写负载均衡，从实例列表中挑选一个实例
        ServiceInstance instance = instances.get(RandomUtil.randomInt(instances.size()));
        //2.3利用RestTamplate发起http请求
        ResponseEntity<List<ItemDTO>> response = restTemplate.exchange(
                instance.getUri()+"/items?ids={ids}",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<ItemDTO>>() {
                },
                Map.of("ids", CollUtil.join(itemIds, ","))
        );
        //2.4 解析响应
        if(!response.getStatusCode().is2xxSuccessful()){
            //查询失败
            return;
        }
        List<ItemDTO> items = response.getBody();*/
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (CollUtils.isEmpty(items)) {
            return;
        }
        // 3.转为 id 到 item的map
        Map<Long, ItemDTO> itemMap = items.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
        // 4.写入vo
        for (CartVO v : vos) {
            ItemDTO item = itemMap.get(v.getItemId());
            if (item == null) {
                continue;
            }
            v.setNewPrice(item.getPrice());
            v.setStatus(item.getStatus());
            v.setStock(item.getStock());
        }
    }

    @Override
    @Transactional
    public void removeByItemIds(Collection<Long> itemIds) {
        // 1.构建删除条件，userId和itemId
        QueryWrapper<Cart> queryWrapper = new QueryWrapper<Cart>();
        queryWrapper.lambda()
                .eq(Cart::getUserId, UserContext.getUser())
                .in(Cart::getItemId, itemIds);
        // 2.删除
        remove(queryWrapper);
    }

    private void checkCartsFull(Long userId) {
        int count = lambdaQuery().eq(Cart::getUserId, userId).count();
        if (count >= cartProperties.getMaxAmount()) {
            throw new BizIllegalException(StrUtil.format("用户购物车课程不能超过{}", cartProperties.getMaxAmount()));
        }
    }

    private boolean checkItemExists(Long itemId, Long userId) {
        int count = lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getItemId, itemId)
                .count();
        return count > 0;
    }
}
