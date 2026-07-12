#!/usr/bin/env python3
"""
单点行人抓拍 — 激活码批量生成器

算法：
  - 激活码 = payload(6位) + checksum(6位)
  - payload: 随机生成 6 位字母数字（大写）
  - checksum = SHA-256(payload + SALT)[:6].upper()
  - SALT = "ZPQNY2026PED"（与 App 端共享）
  - 最终格式：XXXX-XXXX-XXXX（12位，3组4位）

用法：
  python3 generate_codes.py              # 默认生成 100 个
  python3 generate_codes.py -n 500       # 生成 500 个
  python3 generate_codes.py -n 50 -o my_codes.txt  # 指定输出文件

激活价格：¥68/个
有效期：永久
"""

import hashlib
import random
import string
import argparse
import os
from datetime import datetime

SALT = "ZPQNY2026PED"
CHARS = string.ascii_uppercase + string.digits  # A-Z, 0-9 (去除易混淆字符)
# 去除易混淆字符
AMBIGUOUS = set("O0I1L")
SAFE_CHARS = ''.join(c for c in CHARS if c not in AMBIGUOUS)


def generate_payload(length=6):
    """生成随机 payload"""
    return ''.join(random.choice(SAFE_CHARS) for _ in range(length))


def compute_checksum(payload, salt=SALT):
    """计算校验码：SHA-256(payload + salt)[:6]"""
    raw = (payload + salt).encode('utf-8')
    hash_hex = hashlib.sha256(raw).hexdigest()
    return hash_hex[:6].upper()


def generate_code():
    """生成一个激活码（12位，无分隔符）"""
    payload = generate_payload(6)
    checksum = compute_checksum(payload)
    return payload + checksum


def format_code(code):
    """格式化：XXXX-XXXX-XXXX"""
    return f"{code[:4]}-{code[4:8]}-{code[8:12]}"


def validate_code(code):
    """验证激活码是否有效"""
    clean = code.replace('-', '').replace(' ', '').upper()
    if len(clean) != 12:
        return False
    if not all(c in string.ascii_uppercase + string.digits for c in clean):
        return False
    payload = clean[:6]
    checksum = clean[6:]
    expected = compute_checksum(payload)
    return expected == checksum


def main():
    parser = argparse.ArgumentParser(description='单点行人抓拍 - 激活码生成器')
    parser.add_argument('-n', '--count', type=int, default=100, help='生成数量（默认100）')
    parser.add_argument('-o', '--output', type=str, default=None, help='输出文件路径')
    parser.add_argument('--format', choices=['plain', 'formatted', 'csv'], default='formatted',
                        help='输出格式：plain(纯码) / formatted(带横线) / csv(含序号)')
    parser.add_argument('--verify', action='store_true', help='生成后自验')
    args = parser.parse_args()

    print(f"╔══════════════════════════════════════════════════╗")
    print(f"║     单点行人抓拍 · 激活码生成器 v1.0             ║")
    print(f"╠══════════════════════════════════════════════════╣")
    print(f"║  生成数量: {args.count:>6}                            ║")
    print(f"║  单价:     ¥68                                 ║")
    print(f"║  有效期:   永久                                ║")
    print(f"║  算法:     SHA-256(payload + SALT)[:6]          ║")
    print(f"╚══════════════════════════════════════════════════╝")
    print()

    codes = set()
    attempts = 0
    max_attempts = args.count * 10

    while len(codes) < args.count and attempts < max_attempts:
        code = generate_code()
        codes.add(code)
        attempts += 1

    codes = sorted(codes)

    # 自验证
    if args.verify:
        invalid = [c for c in codes if not validate_code(c)]
        if invalid:
            print(f"❌ 验证失败：{len(invalid)} 个无效码！")
            return
        print(f"✅ 自验通过：{len(codes)} 个激活码全部有效")
        print()

    # 输出
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    lines = []

    if args.format == 'plain':
        lines.append(f"# 单点行人抓拍激活码")
        lines.append(f"# 生成时间: {timestamp}")
        lines.append(f"# 数量: {len(codes)}")
        lines.append(f"# 单价: ¥68")
        lines.append("")
        for code in codes:
            lines.append(code)

    elif args.format == 'formatted':
        lines.append(f"# 单点行人抓拍激活码")
        lines.append(f"# 生成时间: {timestamp}")
        lines.append(f"# 数量: {len(codes)}")
        lines.append(f"# 单价: ¥68")
        lines.append("")
        for code in codes:
            lines.append(format_code(code))

    elif args.format == 'csv':
        lines.append("序号,激活码,状态,激活时间,设备指纹")
        for i, code in enumerate(codes, 1):
            lines.append(f"{i},{format_code(code)},未使用,,")

    output = '\n'.join(lines) + '\n'

    # 写文件或打印
    if args.output:
        filepath = os.path.abspath(args.output)
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(output)
        print(f"📁 已保存到: {filepath}")
        print(f"📊 共 {len(codes)} 个激活码")
    else:
        print(output)

    # 统计
    print(f"──────────────────────────")
    print(f"总计: {len(codes)} 个激活码")
    print(f"总价: ¥{len(codes) * 68}")
    print(f"时间: {timestamp}")


if __name__ == '__main__':
    main()
