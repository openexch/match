function generateOrderData(context, events, done) {
  // Generate random 7-character userId
  const userId = generateRandomString(7);

  // Fixed market
  const market = "BTC-USD";

  // Random order type (LIMIT or MARKET)
  const orderType = Math.random() > 0.5 ? "LIMIT" : "MARKET";

  // Random order side (ASK or BID)
  const orderSide = Math.random() > 0.5 ? "ASK" : "BID";

  // Random price between 3000-4000
  const price = parseFloat((Math.random() * 1000 + 3000).toFixed(2));

  // Random quantity between 0.001-5
  const quantity = parseFloat((Math.random() * 4.999 + 0.001).toFixed(3));

  // Calculate total price
  const totalPrice = parseFloat((price * quantity).toFixed(2));

  // Set variables in context
  context.vars.userId = userId;
  context.vars.market = market;
  context.vars.orderType = orderType;
  context.vars.orderSide = orderSide;
  context.vars.price = price;
  context.vars.quantity = quantity;
  context.vars.totalPrice = totalPrice;

  return done();
}

function generateRandomString(length) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let result = '';
  for (let i = 0; i < length; i++) {
    result += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return result;
}

module.exports = {
  generateOrderData
};