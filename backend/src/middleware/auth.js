/**
 * Optional auth middleware. If WS_AUTH_TOKEN is set, require token.
 */

const { WS_AUTH_TOKEN } = require("../config");

function optionalAuth(req, res, next) {
  if (!WS_AUTH_TOKEN) return next();
  const header = req.headers["authorization"] || "";
  const bearer = header.startsWith("Bearer ") ? header.slice(7) : null;
  const token = req.headers["x-auth-token"] || bearer;
  if (token !== WS_AUTH_TOKEN) {
    return res.status(401).json({ error: "Unauthorized" });
  }
  return next();
}

module.exports = {
  optionalAuth,
};
