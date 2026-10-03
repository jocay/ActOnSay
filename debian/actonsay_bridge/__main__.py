import logging

import uvicorn

from .api import create_app


if __name__ == "__main__":
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s %(message)s")
    # 单进程是数据库队列和 MCP actor 的运行边界；地址不可通过参数暴露到局域网。
    uvicorn.run(create_app(), host="127.0.0.1", port=18765, workers=1, access_log=False, log_level="warning", limit_concurrency=64, timeout_keep_alive=5)
