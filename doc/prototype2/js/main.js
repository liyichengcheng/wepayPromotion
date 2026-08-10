// ====================== 1、阶梯定价计算逻辑 ======================
/**
 * @param payUserCount 累计付费总人数
 * @returns 当前售价
 * 规则：
 * 1. 前1000名：6元
 * 2. 每增加1万名付费用户，价格+1元
 * 3. 最高封顶 20元
 */
function calcArticlePrice(payUserCount) {
  const basePrice = 6;
  const maxPrice = 20;
  if (payUserCount <= 1000) return basePrice;
  // 超出1000人的数量
  const exceedNum = payUserCount - 1000;
  // 每1万人涨1元
  const addVal = Math.floor(exceedNum / 10000);
  const nowPrice = basePrice + addVal;
  return Math.min(nowPrice, maxPrice);
}

// ====================== 2、返佣计算 ======================
/**
 * 佣金 = 实付金额 * 30%，保底2元
 */
function getCommission(price) {
  const commission = price * 0.3;
  return commission < 2 ? 2 : commission;
}

// 模拟后台付费人数（对接后端替换真实接口）
let totalPayUser = 850;
const currentPrice = calcArticlePrice(totalPayUser);
document.getElementById('currentPrice').innerText = currentPrice;

// ====================== 3、禁止截图、禁止复制（小程序网页端基础防护） ======================
// 禁止右键
document.addEventListener('contextmenu', e => e.preventDefault());
// 禁止截图快捷键、打印
document.addEventListener('keydown', (e) => {
  // 屏蔽打印、截图相关按键
  if (e.key === 'PrintScreen' || (e.ctrlKey && e.key === 'p')) {
    e.preventDefault();
  }
});
// 小程序内还可配合 wx.setVisualEffectOnCapture 系统级禁止截屏（真实微信小程序API）

// ====================== 4、支付、分享交互 ======================
const payMask = document.getElementById('payMask');
const successModal = document.getElementById('successModal');
const payBtn = document.getElementById('payBtn');
const shareBtn = document.getElementById('shareBtn');
const closeModal = document.querySelector('.close-modal');

// 支付按钮
payBtn.addEventListener('click', () => {
  // 真实环境：调用微信小程序支付API wx.requestPayment
  successModal.style.display = 'flex';
});
// 关闭支付成功弹窗
closeModal.addEventListener('click', () => {
  successModal.style.display = 'none';
  payMask.style.display = 'none'; // 支付成功移除遮罩，展示全文
});

// 分享按钮（小程序分享API）
shareBtn.addEventListener('click', () => {
  // 真实小程序：wx.updateShareMenu + wx.showShareMenu 携带推广者参数
  alert('已生成专属分享链接，好友通过链接付费你自动结算返佣');
});

console.log("当前售价：", currentPrice, "元");
console.log("好友付费一单可获得佣金：", getCommission(currentPrice), "元");