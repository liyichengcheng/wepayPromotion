// utils/price.js - 定价与返佣核心逻辑

/**
 * 阶梯定价计算
 * @param {number} payUserCount 累计付费人数
 * @param {object} config 定价配置
 * @returns {number} 当前售价
 *
 * 规则：
 * 1. 前 threshold 名：basePrice 元
 * 2. 每增加 stepUsers 人，价格 + stepAmount 元
 * 3. 最高 maxPrice 元封顶
 */
function calcArticlePrice(payUserCount, config) {
  const {
    basePrice = 6,
    threshold = 1000,
    stepUsers = 10000,
    stepAmount = 1,
    maxPrice = 20
  } = config || {};

  if (payUserCount <= threshold) return basePrice;

  const exceedNum = payUserCount - threshold;
  const addVal = Math.floor(exceedNum / stepUsers);
  const nowPrice = basePrice + addVal * stepAmount;

  return Math.min(nowPrice, maxPrice);
}

/**
 * 计算返佣金额
 * @param {number} price 订单实付金额
 * @param {object} config 返佣配置
 * @returns {number} 返佣金额
 *
 * 规则：实付 * 佣金比例，保底 minCommission 元
 */
function calcCommission(price, config) {
  const { commissionRate = 0.3, minCommission = 2 } = config || {};
  const commission = price * commissionRate;
  return commission < minCommission ? minCommission : commission;
}

/**
 * 获取价格档位信息（用于展示）
 * @param {number} payUserCount 累计付费人数
 * @param {object} config 定价配置
 * @returns {object} 档位信息
 */
function getPriceTier(payUserCount, config) {
  const {
    basePrice = 6,
    threshold = 1000,
    stepUsers = 10000,
    stepAmount = 1,
    maxPrice = 20
  } = config || {};

  if (payUserCount <= threshold) {
    return {
      tierIndex: 0,
      tierStart: 1,
      tierEnd: threshold,
      price: basePrice
    };
  }

  const tierIndex = Math.floor((payUserCount - threshold) / stepUsers) + 1;
  const currentPrice = Math.min(basePrice + tierIndex * stepAmount, maxPrice);
  const tierStart = threshold + (tierIndex - 1) * stepUsers + 1;
  const tierEnd = threshold + tierIndex * stepUsers;

  return {
    tierIndex,
    tierStart,
    tierEnd,
    price: currentPrice,
    isMax: currentPrice >= maxPrice
  };
}

/**
 * 格式化金额（保留2位小数）
 */
function formatPrice(price) {
  return Number(price).toFixed(2);
}

module.exports = {
  calcArticlePrice,
  calcCommission,
  getPriceTier,
  formatPrice
};
