import { useEffect, useMemo, useState } from 'react';
import { Card, CardBody, CardHeader } from '@heroui/card';
import { Button } from '@heroui/button';
import { Input } from '@heroui/input';
import { Chip } from '@heroui/chip';
import { Spinner } from '@heroui/spinner';
import toast from 'react-hot-toast';

import { checkNodeQuality, getNodeList } from '@/api';

type NodeItem = {
  id: number;
  name: string;
  serverIp?: string;
  status: number;
  version?: string;
};

type QualityResult = {
  nodeId: number;
  nodeName: string;
  target: string;
  port: number;
  count: number;
  successCount: number;
  averageTime: number;
  minTime: number;
  maxTime: number;
  jitter: number;
  packetLoss: number;
  samples: number[];
  quality: 'excellent' | 'good' | 'fair' | 'poor';
  timestamp: number;
  lastError?: string;
};

const qualityText: Record<QualityResult['quality'], string> = {
  excellent: '优秀',
  good: '良好',
  fair: '一般',
  poor: '较差',
};

const qualityColor: Record<QualityResult['quality'], 'success' | 'primary' | 'warning' | 'danger'> = {
  excellent: 'success',
  good: 'primary',
  fair: 'warning',
  poor: 'danger',
};

export default function NetworkQualityPage() {
  const [nodes, setNodes] = useState<NodeItem[]>([]);
  const [selectedNodeId, setSelectedNodeId] = useState<number | null>(null);
  const [target, setTarget] = useState('www.cloudflare.com');
  const [port, setPort] = useState('443');
  const [loadingNodes, setLoadingNodes] = useState(true);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<QualityResult | null>(null);

  useEffect(() => {
    loadNodes();
  }, []);

  const onlineNodes = useMemo(() => nodes.filter(node => node.status === 1), [nodes]);

  const loadNodes = async () => {
    setLoadingNodes(true);
    try {
      const response = await getNodeList();
      if (response.code !== 0) {
        toast.error(response.msg || '加载节点失败');
        return;
      }
      const list = (response.data || []) as NodeItem[];
      setNodes(list);
      const firstOnline = list.find(node => node.status === 1);
      if (firstOnline) {
        setSelectedNodeId(current => current ?? firstOnline.id);
      }
    } catch (error) {
      toast.error('加载节点失败');
    } finally {
      setLoadingNodes(false);
    }
  };

  const runTest = async () => {
    if (!selectedNodeId) {
      toast.error('请选择在线节点');
      return;
    }
    const portNumber = Number(port);
    if (!Number.isInteger(portNumber) || portNumber < 1 || portNumber > 65535) {
      toast.error('端口必须在 1-65535 之间');
      return;
    }
    if (!target.trim()) {
      toast.error('请输入检测目标');
      return;
    }

    setTesting(true);
    setResult(null);
    try {
      const response = await checkNodeQuality({
        nodeId: selectedNodeId,
        target: target.trim(),
        port: portNumber,
        count: 5,
      });
      if (response.code === 0) {
        setResult(response.data as QualityResult);
      } else {
        toast.error(response.msg || '网络质量检测失败');
      }
    } catch (error) {
      toast.error('网络质量检测失败');
    } finally {
      setTesting(false);
    }
  };

  const metric = (label: string, value: string, hint: string) => (
    <div className="rounded-xl border border-divider bg-default-50 dark:bg-default-100/30 p-4">
      <div className="text-xs text-default-500">{label}</div>
      <div className="mt-1 text-xl font-semibold font-mono">{value}</div>
      <div className="mt-1 text-xs text-default-400">{hint}</div>
    </div>
  );

  return (
    <div className="px-3 lg:px-6 py-6 space-y-5">
      <div>
        <h1 className="text-xl font-semibold">网络质量检测</h1>
        <p className="text-sm text-default-500 mt-1">
          检测命令由所选 VPS Runtime 实际执行，结果不是面板服务器到目标的延迟。
        </p>
      </div>

      <Card className="border border-divider shadow-sm">
        <CardHeader className="pb-2">
          <div className="font-medium">检测参数</div>
        </CardHeader>
        <CardBody className="space-y-4">
          {loadingNodes ? (
            <div className="flex items-center gap-2 py-6 justify-center text-default-500">
              <Spinner size="sm" /> 正在加载节点...
            </div>
          ) : (
            <>
              <div>
                <label className="text-sm text-default-600 block mb-1.5">执行节点</label>
                <select
                  className="w-full rounded-xl border border-divider bg-background px-3 py-2.5 text-sm outline-none"
                  value={selectedNodeId ?? ''}
                  onChange={event => setSelectedNodeId(event.target.value ? Number(event.target.value) : null)}
                >
                  <option value="">请选择在线节点</option>
                  {onlineNodes.map(node => (
                    <option key={node.id} value={node.id}>
                      {node.name}{node.serverIp ? ` · ${node.serverIp}` : ''}{node.version ? ` · v${node.version}` : ''}
                    </option>
                  ))}
                </select>
                {onlineNodes.length === 0 && (
                  <div className="text-xs text-danger mt-2">当前没有在线节点，无法发起远端检测。</div>
                )}
              </div>

              <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                <div className="md:col-span-2">
                  <Input
                    label="目标地址"
                    value={target}
                    onChange={event => setTarget(event.target.value)}
                    placeholder="www.cloudflare.com"
                    variant="bordered"
                  />
                </div>
                <Input
                  label="TCP 端口"
                  value={port}
                  onChange={event => setPort(event.target.value)}
                  type="number"
                  min={1}
                  max={65535}
                  variant="bordered"
                />
              </div>

              <div className="flex justify-end">
                <Button
                  color="primary"
                  onPress={runTest}
                  isLoading={testing}
                  isDisabled={!selectedNodeId || onlineNodes.length === 0}
                >
                  开始检测（5 次）
                </Button>
              </div>
            </>
          )}
        </CardBody>
      </Card>

      {testing && (
        <Card className="border border-divider shadow-sm">
          <CardBody className="py-10 flex flex-col items-center gap-3 text-default-500">
            <Spinner />
            <div className="text-sm">正在由 VPS 执行 TCP 探测，失败样本最多等待约 3 秒...</div>
          </CardBody>
        </Card>
      )}

      {result && !testing && (
        <Card className="border border-divider shadow-sm">
          <CardHeader className="flex justify-between items-center">
            <div>
              <div className="font-medium">{result.nodeName}</div>
              <div className="text-xs text-default-500 mt-1">{result.target}:{result.port}</div>
            </div>
            <Chip color={qualityColor[result.quality]} variant="flat">
              {qualityText[result.quality]}
            </Chip>
          </CardHeader>
          <CardBody className="space-y-4">
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
              {metric('平均延迟', result.averageTime >= 0 ? `${result.averageTime.toFixed(2)} ms` : '-', '成功 TCP 建连平均耗时')}
              {metric('抖动', result.jitter >= 0 ? `${result.jitter.toFixed(2)} ms` : '-', '相邻成功样本延迟变化')}
              {metric('丢包', `${result.packetLoss.toFixed(1)}%`, `${result.successCount}/${result.count} 次成功`)}
              {metric('范围', result.minTime >= 0 ? `${result.minTime.toFixed(1)}–${result.maxTime.toFixed(1)} ms` : '-', '最低 / 最高延迟')}
            </div>

            <div className="rounded-xl border border-divider p-4">
              <div className="text-sm font-medium mb-3">样本</div>
              <div className="flex flex-wrap gap-2">
                {result.samples.length > 0 ? result.samples.map((sample, index) => (
                  <Chip key={`${sample}-${index}`} size="sm" variant="flat">
                    #{index + 1} {Number(sample).toFixed(2)} ms
                  </Chip>
                )) : <span className="text-sm text-danger">没有成功样本</span>}
              </div>
              {result.lastError && (
                <div className="text-xs text-danger mt-3 break-all">最近错误：{result.lastError}</div>
              )}
            </div>

            <div className="text-xs text-default-400 text-right">
              检测时间：{new Date(result.timestamp).toLocaleString('zh-CN')}
            </div>
          </CardBody>
        </Card>
      )}
    </div>
  );
}
