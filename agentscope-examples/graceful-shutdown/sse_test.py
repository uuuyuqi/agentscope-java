import requests
import json
import sys
import argparse

def test_stream(session_id=None):
    url = "http://localhost:8080/api/orders/process"
    headers = {"Content-Type": "application/json"}
    data = {
        "orderId": "ORDER-CLI-001", 
        "products": [{"id": "PROD-TEST", "quantity": 1}]
    }
    
    # 如果提供了 sessionId，添加到请求中
    if session_id:
        data["sessionId"] = session_id
        print(f"📌 使用指定的 SessionId: {session_id}")

    print(f"🚀 发起请求: {url}")
    print("--------------------------------------------------")

    try:
        # stream=True 是关键，开启流式读取
        with requests.post(url, json=data, headers=headers, stream=True) as response:
            for line in response.iter_lines():
                if line:
                    decoded_line = line.decode('utf-8')
                    # SSE 格式通常以 "data:" 开头
                    if decoded_line.startswith('data:'):
                        json_str = decoded_line.replace('data:', '', 1)
                        try:
                            event = json.loads(json_str)
                            
                            # 1. 获取要显示的内容
                            content = event.get("content")
                            
                            # 2. 及其它状态信息
                            status = event.get("status")
                            session = event.get("sessionId")
                            
                            # 首次显示返回的 sessionId
                            if session and not hasattr(test_stream, '_session_shown'):
                                print(f"📎 SessionId: {session}")
                                test_stream._session_shown = True
                            
                            # 3. 打印效果：不换行，且刷新缓冲区
                            if content:
                                sys.stdout.write(content)
                                sys.stdout.flush()
                            
                            # 如果结束了
                            if status == 'completed':
                                print("\n\n✅ 订单处理完成!")
                            elif status == 'interrupted':
                                print(f"\n\n🛑 服务中断! SessionId: {event.get('sessionId')}")
                                print("💡 提示: 使用 --session-id 参数恢复此会话")
                                
                        except json.JSONDecodeError:
                            pass
    except ImportError:
        print("❌ 请先安装 requests 库: pip install requests")
    except Exception as e:
        print(f"\n❌ 发生错误: {e}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="测试优雅下线 SSE 流式响应")
    parser.add_argument("--session-id", "-s", type=str, help="指定 sessionId (用于恢复中断的会话)")
    args = parser.parse_args()
    
    test_stream(args.session_id)
