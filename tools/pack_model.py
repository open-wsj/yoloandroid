#!/usr/bin/env python3
"""把 ncnn 模型打包成 App 可导入的 .yolozip 模型包。

用法示例（检测模型）：
  python tools/pack_model.py \
      --param yolo26m.ncnn.param --bin yolo26m.ncnn.bin --labels labels.txt \
      --task detect --id my-detector --name "My Detector" --version 1.0.0 \
      --input-size 640 --output my-detector.yolozip

用法示例（分类模型）：
  python tools/pack_model.py \
      --param yolo26m-cls.ncnn.param --bin yolo26m-cls.ncnn.bin --labels imagenet_labels.txt \
      --task classify --id my-classifier --name "My Classifier" --version 1.0.0 \
      --input-size 224 --output my-classifier.yolozip

打包后的 zip 结构固定为：
  manifest.json
  model.ncnn.param
  model.ncnn.bin
  labels.txt
"""

import argparse
import json
import sys
import zipfile
from pathlib import Path

NCNN_MAGIC = "7767517"


def parse_triple(text, default):
    if text is None:
        return list(default)
    parts = [p.strip() for p in text.split(",")]
    if len(parts) != 3:
        raise SystemExit("mean/std 需要 3 个逗号分隔的数值，例如 0,0,0")
    return [float(p) for p in parts]


def read_labels(path):
    labels = [line.strip() for line in path.read_text(encoding="utf-8").splitlines()]
    labels = [line for line in labels if line]
    if not labels:
        raise SystemExit("标签文件为空")
    return labels


def check_param(path):
    with path.open("r", encoding="utf-8", errors="replace") as f:
        first = f.readline().strip()
    if first != NCNN_MAGIC:
        raise SystemExit(f"param 文件不是 ncnn 格式（首行应为 {NCNN_MAGIC}，实际 {first!r}）")


def main():
    parser = argparse.ArgumentParser(description="打包 .yolozip 模型包")
    parser.add_argument("--param", required=True, help="model.ncnn.param 路径")
    parser.add_argument("--bin", required=True, help="model.ncnn.bin 路径")
    parser.add_argument("--labels", required=True, help="标签文件，每行一个类别")
    parser.add_argument("--task", required=True, choices=["detect", "classify"])
    parser.add_argument("--id", required=True, help="模型唯一标识，建议英文+连字符")
    parser.add_argument("--name", required=True, help="展示名称")
    parser.add_argument("--version", default="1.0.0")
    parser.add_argument("--input-size", type=int, help="正方形输入尺寸；检测默认 640，分类默认 224")
    parser.add_argument("--width", type=int, help="非正方形输入的宽（仅检测）")
    parser.add_argument("--height", type=int, help="非正方形输入的高（仅检测）")
    parser.add_argument("--scale", type=float, default=1.0 / 255.0)
    parser.add_argument("--mean", help="归一化均值，逗号分隔，如 0,0,0")
    parser.add_argument("--std", help="归一化标准差，逗号分隔，如 1,1,1")
    parser.add_argument("--pad", type=float, default=114.0, help="letterbox 填充值（检测）")
    parser.add_argument("--prob", type=float, default=0.25, help="默认置信度阈值（检测）")
    parser.add_argument("--nms", type=float, default=0.45, help="默认 NMS 阈值（检测）")
    parser.add_argument("--topk", type=int, default=5, help="分类展示 Top-K")
    parser.add_argument("--apply-softmax", action="store_true",
                        help="分类模型输出为 logits 时，由 App 补 Softmax")
    parser.add_argument("--output", required=True, help="输出 .yolozip 路径")
    args = parser.parse_args()

    param = Path(args.param)
    bin_path = Path(args.bin)
    labels_path = Path(args.labels)
    for p, label in ((param, "param"), (bin_path, "bin"), (labels_path, "labels")):
        if not p.is_file():
            raise SystemExit(f"{label} 文件不存在：{p}")
    check_param(param)
    labels = read_labels(labels_path)

    default_size = 640 if args.task == "detect" else 224
    width = args.width or args.input_size or default_size
    height = args.height or args.input_size or default_size
    if args.task == "detect" and (width % 32 != 0 or height % 32 != 0):
        raise SystemExit("检测模型输入尺寸需为 32 的倍数")
    if args.task == "classify" and width != height:
        raise SystemExit("分类模型的输入宽高必须相同")

    manifest = {
        "schemaVersion": 1,
        "id": args.id,
        "name": args.name,
        "version": args.version,
        "task": args.task,
        "input": {
            "width": width,
            "height": height,
            "colorOrder": "RGB",
            "scale": args.scale,
            "mean": parse_triple(args.mean, [0.0, 0.0, 0.0]),
            "std": parse_triple(args.std, [1.0, 1.0, 1.0]),
        },
        "classes": {
            "numClasses": len(labels),
            "labelsFile": "labels.txt",
        },
        "files": {
            "param": "model.ncnn.param",
            "bin": "model.ncnn.bin",
        },
    }

    if args.task == "detect":
        manifest["input"]["padValue"] = args.pad
        manifest["postprocess"] = {
            "type": "yolo_anchor",
            "probThreshold": args.prob,
            "nmsThreshold": args.nms,
        }
    else:
        manifest["postprocess"] = {
            "type": "softmax_topk",
            "applySoftmax": bool(args.apply_softmax),
            "topk": args.topk,
        }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        zf.write(param, "model.ncnn.param")
        zf.write(bin_path, "model.ncnn.bin")
        zf.write(labels_path, "labels.txt")

    size_mb = output.stat().st_size / (1024 * 1024)
    print(f"已生成 {output}（{size_mb:.1f} MB）")
    print(f"  task={args.task}  classes={len(labels)}  input={width}x{height}")


if __name__ == "__main__":
    sys.exit(main())
