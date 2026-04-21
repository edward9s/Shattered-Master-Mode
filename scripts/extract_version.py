import re
import sys

def extract_version(file_path):
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
            
        # 匹配 public static String version() { return "版本號";
        pattern = r'public\s+static\s+String\s+version\(\)\s*\{\s*return\s*"([^"]+)";'
        match = re.search(pattern, content)
        
        if match:
            return match.group(1)
        else:
            return "找不到版本號"
            
    except FileNotFoundError:
        return "檔案不存在"

# 執行範例
if __name__ == "__main__":
    # 若透過終端機執行：python script.py 路徑/ModGame.java
    target_file = sys.argv[1] if len(sys.argv) > 1 else "ModGame.java"
    print(extract_version(target_file))
