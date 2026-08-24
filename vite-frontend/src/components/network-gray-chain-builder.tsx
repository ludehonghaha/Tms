import { useEffect, useMemo, useState } from 'react';
import { Card, CardBody, CardHeader } from '@heroui/card';
import { Button } from '@heroui/button';
import { Input } from '@heroui/input';
import { Chip } from '@heroui/chip';
import { Divider } from '@heroui/divider';
import toast from 'react-hot-toast';

import { getNetworkTopology, saveNetworkChain } from '@/api/network-orchestration';

type AnyMap = Record<string, any>;

interface Props {
  onCreated?: (chainId?: number) => void | Promise<void>;
}

const nodeId = (value: string) => {
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : null;
};

export default function NetworkGrayChainBuilder({ onCreated }: Props) {
  const [nodes, setNodes] = useState<AnyMap[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [name, setName] = useState('gray-network-chain');
  const [entry, setEntry] = useState('');
  const [relay, setRelay] = useState('');
  const [egress, setEgress] = useState('');

  const onlineNodes = useMemo(
    () => nodes.filter((node) => Number(node.status) === 1),
    [nodes],
  );

  const loadNodes = async () => {
    setLoading(true);
    try {
      const res = await getNetworkTopology();
      if (res.code === 0) {
        const rows = Array.isArray((res.data as AnyMap)?.nodes) ? (res.data as AnyMap).nodes : [];
        setNodes(rows);
      } else {
        toast.error(res.msg || '读取节点拓扑失败');
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadNodes();
  }, []);

  const createChain = async () => {
    const entryId = nodeId(entry);
    const relayId = relay ? nodeId(relay) : null;
    const egressId = nodeId(egress);

    if (!name.trim()) {
      toast.error('请输入 Chain 名称');
      return;
    }
    if (!entryId || !egressId) {
      toast.error('入口和出口不能为空');
      return;
    }

    const ids = [entryId, relayId, egressId].filter((v): v is number => !!v);
    for (let i = 1; i < ids.length; i += 1) {
      if (ids[i] === ids[i - 1]) {
        toast.error('相邻 Hop 不能是同一台节点');
        return;
      }
    }
    if (ids.length < 2) {
      toast.error('至少需要两台不同节点');
      return;
    }

    const hops = ids.map((id, index) => ({
      hopOrder: index + 1,
      hopType: 'NODE',
      nodeId: id,
      transport: 'AUTO',
      status: 1,
    }));

    setSaving(true);
    try {
      const res = await saveNetworkChain({
        name: name.trim(),
        protocol: 'AUTO',
        failoverEnabled: 0,
        remark: 'gray chain created from Network Orchestration UI',
        status: 1,
        hops,
      });
      if (res.code !== 0) {
        toast.error(res.msg || '创建 Chain 失败');
        return;
      }
      const createdId = Number((res.data as AnyMap)?.id) || undefined;
      toast.success('灰度 Chain 已创建；尚未创建 Tunnel/Forward');
      if (onCreated) await onCreated(createdId);
    } catch (error) {
      console.error(error);
      toast.error('创建 Chain 失败');
    } finally {
      setSaving(false);
    }
  };

  const renderNodeOptions = () => (
    <>
      <option value="">请选择</option>
      {nodes.map((node) => (
        <option key={node.id} value={node.id}>
          {node.name || `Node #${node.id}`} · {node.server_ip || node.ip || '-'} · {Number(node.status) === 1 ? '在线' : '离线'}
        </option>
      ))}
    </>
  );

  return (
    <Card>
      <CardHeader className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 className="text-lg font-semibold">快速创建灰度 Chain</h2>
          <div className="text-xs text-default-500">这里只写编排模型，不创建 Tunnel / Forward，不下发 Agent。</div>
        </div>
        <div className="flex gap-2">
          <Chip size="sm" color="success" variant="flat">在线 {onlineNodes.length}</Chip>
          <Chip size="sm" variant="flat">总节点 {nodes.length}</Chip>
        </div>
      </CardHeader>
      <Divider />
      <CardBody className="gap-4">
        <Input
          label="Chain 名称"
          value={name}
          onValueChange={setName}
          placeholder="例如 gray-7cm-hk-jp"
        />

        <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-default-600">1. 入口节点</span>
            <select
              className="h-10 rounded-medium border border-default-200 bg-content1 px-3 outline-none focus:border-primary"
              value={entry}
              onChange={(event) => setEntry(event.target.value)}
              disabled={loading}
            >
              {renderNodeOptions()}
            </select>
          </label>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-default-600">2. 中转节点（可选）</span>
            <select
              className="h-10 rounded-medium border border-default-200 bg-content1 px-3 outline-none focus:border-primary"
              value={relay}
              onChange={(event) => setRelay(event.target.value)}
              disabled={loading}
            >
              {renderNodeOptions()}
            </select>
          </label>

          <label className="flex flex-col gap-1 text-sm">
            <span className="text-default-600">3. 出口节点</span>
            <select
              className="h-10 rounded-medium border border-default-200 bg-content1 px-3 outline-none focus:border-primary"
              value={egress}
              onChange={(event) => setEgress(event.target.value)}
              disabled={loading}
            >
              {renderNodeOptions()}
            </select>
          </label>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <Button color="primary" onPress={createChain} isLoading={saving} isDisabled={loading || saving}>
            创建灰度 Chain
          </Button>
          <Button variant="light" onPress={loadNodes} isDisabled={saving}>刷新节点</Button>
          <span className="text-xs text-default-500">建议先选 3 台非生产或可使用测试端口的节点。</span>
        </div>
      </CardBody>
    </Card>
  );
}
