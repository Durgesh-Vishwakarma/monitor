/**
 * URL parsing helper
 */

function parseReqUrl(rawUrl) {
  try {
    const u = new URL(rawUrl, "http://localhost");
    return { pathname: u.pathname, searchParams: u.searchParams };
  } catch (_) {
    return {
      pathname: rawUrl.split("?")[0] || "/",
      searchParams: new URLSearchParams(),
    };
  }
}

module.exports = {
  parseReqUrl,
};
