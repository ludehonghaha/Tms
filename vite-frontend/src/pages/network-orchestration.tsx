import { useEffect, useMemo, useState } from 'react';
import { Card, CardBody, CardHeader } from '@heroui/card';
import { Button } from '@heroui/button';
import { Input } from '@heroui/input';
import { Chip } from '@heroui/chip';
import { Spinner } from '@heroui/spinner';
import { Divider } from '@heroui/divider';
import { Alert } from '@heroui/alert';
import toast from 'react-hot-toast';

import {
  dryRunNetworkChain,
  getNetworkChains,
  getNetworkDeployments,
  getNetworkGroups,
  NetworkChainRequest,
  preflightNetworkChain,
} from '@/api/network-orchestration';
import NetworkGrayChainBuilder from '@/components/network-gray-chain-builder';

type AnyMap = Record<string, any>;

interface ChainRow extends AnyMap {
  id: number;
  name: string;
  protocol?: string;
  status?: number;
  hops?: AnyMap[];
}

interface GroupRow extends AnyMap {
  id: number;
  name: string;
  role?: string;
  strategy?: string;
  members?: AnyMap[];
}

const valueOf = (row: AnyMap | null | undefined, ...keys: string[]) => {
  if (!row) return undefined;
  for (const key of keys) {
    if (row[key] !== undefined && row[key] !== null) return row[key];
  }
  return undefined;
};

const displayTime = (value: any) => {
  if (!value) return '-';
  const n = Number(value);
  if (!Number.isFinite(n)) return String(value);
  try {
    return new Date(n).toLocaleString();
  } catch {
    return String(value);
  }
};

const shortFingerprint = (value: any) => {
  const text = value ? String(value) : '';
  return text.length > 20 ? `${text.slice(0, 12)}…${text.slice(-6)}` : text || '-';
};

const statusColor = (state?: string) => {
  switch ((state || '').toUpperCase()) {
    case 'ACTIVE':
    case 'SUCCESS':
    case 'ROLLED_BACK':
      return 'success' as const;
    case 'APPLYING':
    case 'ROLLING_BACK':
      return 'warning' as const;
    case 'FAILED':
    case 'ROLLBACK_FAILED':
    case 'FAILED_ROLLED_BACK':
      return 'danger' as const;
    default:
      return 'default' as const;
  }
};

export default function NetworkOrchestrationPage() {
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState<'dry' | 'preflight' | null>(null);
  const [chains, setChains] = useState<ChainRow[]>([]);
  const [groups, setGroups] = useState<GroupRow[]>([]);
  const [deployments, setDeployments] = useState<AnyMap[]>([]);
  const [applyEnabled, setApplyEnabled] = useState(false);
  const [selectedChainId, setSelectedChainId] = useState<string>('');
  const [targetHost, setTargetHost] = useState('127.0.0.1');
  const [targetPort, setTargetPort] = useState('8388');
  const [entryPort, setEntryPort] = useState('');
  const [dryResult, setDryResult] = useState<AnyMap | null>(null);
  const [preflightResult, setPreflightResult] = useState<AnyMap | null>(null);

  const selectedChain = useMemo(
    () => chains.find((chain) => String(chain.id) === selectedChainId) || null,
    [chains, selectedChainId],
  );

  const loadData = async () => {
    setLoading(true);
    try {
      const [chainRes, groupRes, deploymentRes] = await Promise.all([
        getNetworkChains(),
        getNetworkGroups(),
        getNetworkDeployments(),
      ]);

      if (chainRes.code === 0) {
        const rows = Array.isArray(chainRes.data) ? chainRes.data : [];
        setChains(rows);
        setSelectedChainId((current) => current || (rows[0]?.id ? String(rows[0].id) : ''));
      } else {
        toast.error(chainRes.msg || '获取链路失败');
      }

      if (groupRes.code === 0) {
        setGroups(Array.isArray(groupRes.data) ? groupRes.data : []);
      }

      if (deploymentRes.code === 0) {
        const payload = deploymentRes.data as AnyMap | AnyMap[] | undefined;
        if (Array.isArray(payload)) {
          setDeployments(payload);
          setApplyEnabled(false);
        } else {
          setDeployments(Array.isArray(payload?.deployments) ? payload.deployments : []);
          setApplyEnabled(payload?.applyEnabled === true);
        }
      }
    } catch (error) {
      console.error(error);
      toast.error('加载网络编排数据失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleGrayChainCreated = async (chainId?: number) => {
    await loadData();
    if (chainId) setSelectedChainId(String(chainId));
    setDryResult(null);
    setPreflightResult(null);
  };

  const buildRequest = (): NetworkChainRequest | null => {
    const chainId = Number(selectedChainId);
    const port = Number(targetPort);
    const publicPort = entryPort.trim() ? Number(entryPort) : undefined;

    if (!chainId) {
      toast.error('请选择链路');
      return null;
    }
    if (!targetHost.trim()) {
      toast.error('请输入最终目标地址');
      return null;
    }
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      toast.error('目标端口必须为 1-65535');
      return null;
    }
    if (publicPort !== undefined && (!Number.isInteger(publicPort) || publicPort < 1 || publicPort > 65535)) {
      toast.error('入口端口必须为 1-65535');
      return null;
    }

    return {
      chainId,
      targetHost: targetHost.trim(),
      targetPort: port,
      entryPort: publicPort,
      strictHealth: true,
      checkEstimatedOutPorts: true,
      requireTargetReachable: true,
    };
  };

  const runDry = async () => {
    const request = buildRequest();
    if (!request) return;
    setRunning('dry');
    setDryResult(null);
    setPreflightResult(null);
    try {
      const res = await dryRunNetworkChain(request);
      if (res.data) setDryResult(res.data as AnyMap);
      if (res.code === 0) {
        toast.success('Dry-run 完成：未修改数据库和 Agent');
      } else {
        toast.error(res.msg || 'Dry-run 失败');
      }
    } finally {
      setRunning(null);
    }
  };

  const runPreflight = async () => {
    const request = buildRequest();
    if (!request) return;
    setRunning('preflight');
    setPreflightResult(null);
    try {
      const res = await preflightNetworkChain(request);
      if (res.data) setPreflightResult(res.data as AnyMap);
      if (res.code === 0 && (res.data as AnyMap)?.readyForApply === true) {
        toast.success('Preflight 通过，可以进入人工灰度 Apply');
      } else {
        toast.error(res.msg || 'Preflight 未通过');
      }
    } finally {
      setRunning(null);
    }
  };

  if (loading) {
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <Spinner label="加载网络编排…" />
      </div>
    );
  }

  const resolvedHops = Array.isArray(dryResult?.resolvedHops) ? dryResult?.resolvedHops : [];
  const actions = Array.isArray(dryResult?.actions) ? dryResult?.actions : [];
  const checks = Array.isArray(preflightResult?.checks) ? preflightResult?.checks : [];
  const ready = preflightResult?.readyForApply === true;

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col gap-5 pb-10">
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-2xl font-semibold">网络编排（灰度）</h1>
          <Chip color="warning" variant="flat">只读优先</Chip>
          <Chip color="primary" variant="flat">ForwardX 风格</Chip>
          <Chip color={applyEnabled ? 'danger' : 'success'} variant="flat">
            Apply {applyEnabled ? '已启用' : '默认关闭'}
          </Chip>
        </div>
        <p className="text-sm text-default-500">
          当前页面提供灰度 Chain 建模、链路解析、Dry-run、Preflight 和部署记录查看。这里没有 Apply 按钮，避免误改生产链路。
        </p>
      </div>

      <Alert
        color="warning"
        title="安全边界"
        description="创建灰度 Chain 只写隔离的编排模型。Dry-run 不写 Tunnel/Forward、不修改 Agent；Preflight 只调用现有 TcpPing。真正 Apply 仍需服务端显式开启 TMS_NETWORK_APPLY_ENABLED=true，并提供最新 fingerprint。"
      />

      <NetworkGrayChainBuilder onCreated={handleGrayChainCreated} />

      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader className="flex flex-col items-start gap-1">
            <h2 className="text-lg font-semibold">灰度链路计划</h2>
            <span className="text-xs text-default-500">选择 Chain，填写真实最终目标；建议只使用测试端口。</span>
          </CardHeader>
          <Divider />
          <CardBody className="gap-4">
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-default-600">Network Chain</span>
                <select
                  className="h-10 rounded-medium border border-default-200 bg-content1 px-3 outline-none focus:border-primary"
                  value={selectedChainId}
                  onChange={(event) => {
                    setSelectedChainId(event.target.value);
                    setDryResult(null);
                    setPreflightResult(null);
                  }}
                >
                  <option value="">请选择链路</option>
                  {chains.map((chain) => (
                    <option key={chain.id} value={chain.id}>
                      {chain.name} · {chain.protocol || 'AUTO'}
                    </option>
                  ))}
                </select>
              </label>
              <Input
                label="入口端口（可留空自动规划）"
                value={entryPort}
                onValueChange={setEntryPort}
                placeholder="例如 13511"
                type="number"
              />
              <Input
                label="最终目标地址"
                value={targetHost}
                onValueChange={setTargetHost}
                placeholder="127.0.0.1 / 域名 / IP"
              />
              <Input
                label="最终目标端口"
                value={targetPort}
                onValueChange={setTargetPort}
                placeholder="8388"
                type="number"
              />
            </div>

            {selectedChain && (
              <div className="rounded-large bg-default-50 p-3 dark:bg-default-100/20">
                <div className="mb-2 flex items-center justify-between gap-2">
                  <span className="font-medium">{selectedChain.name}</span>
                  <Chip size="sm" color={Number(selectedChain.status) === 1 ? 'success' : 'default'} variant="flat">
                    {Number(selectedChain.status) === 1 ? '启用' : '停用'}
                  </Chip>
                </div>
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  {(selectedChain.hops || []).map((hop, index) => (
                    <div className="contents" key={hop.id || `${hop.hop_order}-${index}`}>
                      {index > 0 && <span className="text-default-400">→</span>}
                      <Chip variant="bordered" size="sm">
                        {valueOf(hop, 'node_name', 'group_name') || `${valueOf(hop, 'hop_type') || 'HOP'} #${valueOf(hop, 'node_id', 'group_id') || '?'}`}
                      </Chip>
                    </div>
                  ))}
                  {(!selectedChain.hops || selectedChain.hops.length === 0) && (
                    <span className="text-default-400">尚未配置 Hop</span>
                  )}
                </div>
              </div>
            )}

            <div className="flex flex-wrap gap-2">
              <Button color="primary" onPress={runDry} isLoading={running === 'dry'} isDisabled={!!running}>
                1. Dry-run
              </Button>
              <Button color="warning" variant="flat" onPress={runPreflight} isLoading={running === 'preflight'} isDisabled={!!running}>
                2. Preflight
              </Button>
              <Button variant="light" onPress={loadData} isDisabled={!!running}>
                刷新
              </Button>
            </div>
          </CardBody>
        </Card>

        <Card>
          <CardHeader className="flex flex-col items-start gap-1">
            <h2 className="text-lg font-semibold">节点组</h2>
            <span className="text-xs text-default-500">入口 / 中转 / 出口选择策略</span>
          </CardHeader>
          <Divider />
          <CardBody className="gap-3">
            {groups.length === 0 && <span className="text-sm text-default-400">尚未创建节点组</span>}
            {groups.map((group) => (
              <div key={group.id} className="rounded-large border border-default-200 p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium">{group.name}</span>
                  <Chip size="sm" variant="flat">{group.role || 'GENERIC'}</Chip>
                </div>
                <div className="mt-2 flex flex-wrap gap-2 text-xs text-default-500">
                  <span>策略：{group.strategy || 'PRIORITY'}</span>
                  <span>成员：{Array.isArray(group.members) ? group.members.length : 0}</span>
                </div>
              </div>
            ))}
          </CardBody>
        </Card>
      </div>

      {dryResult && (
        <Card>
          <CardHeader className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-lg font-semibold">Dry-run 结果</h2>
              <div className="text-xs text-default-500">fingerprint: {shortFingerprint(dryResult.fingerprint)}</div>
            </div>
            <div className="flex gap-2">
              <Chip color={dryResult.executable ? 'success' : 'warning'} variant="flat">
                {dryResult.executable ? '可执行计划' : '仅拓扑预览'}
              </Chip>
              <Chip color="success" variant="flat">零写入</Chip>
            </div>
          </CardHeader>
          <Divider />
          <CardBody className="gap-4">
            <div>
              <div className="mb-2 text-sm font-medium">Resolved Hops</div>
              <div className="flex flex-wrap items-center gap-2">
                {resolvedHops.map((hop: AnyMap, index: number) => (
                  <div className="contents" key={`${hop.nodeId}-${index}`}>
                    {index > 0 && <span className="text-default-400">→</span>}
                    <Chip color={hop.healthy === false ? 'danger' : 'primary'} variant="flat">
                      {hop.nodeName || `Node #${hop.nodeId}`}
                    </Chip>
                  </div>
                ))}
              </div>
            </div>

            <div className="overflow-x-auto">
              <table className="w-full min-w-[720px] text-left text-sm">
                <thead className="text-xs uppercase text-default-500">
                  <tr>
                    <th className="px-2 py-2">顺序</th>
                    <th className="px-2 py-2">动作</th>
                    <th className="px-2 py-2">入口</th>
                    <th className="px-2 py-2">出口 / 目标</th>
                    <th className="px-2 py-2">端口</th>
                  </tr>
                </thead>
                <tbody>
                  {actions.map((action: AnyMap, index: number) => (
                    <tr key={index} className="border-t border-default-100">
                      <td className="px-2 py-2">{index + 1}</td>
                      <td className="px-2 py-2"><Chip size="sm" variant="flat">{action.operation}</Chip></td>
                      <td className="px-2 py-2">{action.fromNodeName || action.inNodeName || action.fromNodeId || '-'}</td>
                      <td className="px-2 py-2">{action.toNodeName || action.remoteAddr || action.toNodeId || '-'}</td>
                      <td className="px-2 py-2">{action.inPort || action.plannedInPort || action.outPort || '-'}</td>
                    </tr>
                  ))}
                  {actions.length === 0 && (
                    <tr><td className="px-2 py-4 text-default-400" colSpan={5}>没有执行动作</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </CardBody>
        </Card>
      )}

      {preflightResult && (
        <Card className={ready ? 'border border-success-300' : 'border border-danger-300'}>
          <CardHeader className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h2 className="text-lg font-semibold">Preflight</h2>
              <div className="text-xs text-default-500">fingerprint: {shortFingerprint(preflightResult.fingerprint)}</div>
            </div>
            <Chip color={ready ? 'success' : 'danger'} variant="flat">
              {ready ? 'READY FOR GRAY APPLY' : 'BLOCKED'}
            </Chip>
          </CardHeader>
          <Divider />
          <CardBody>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[760px] text-left text-sm">
                <thead className="text-xs uppercase text-default-500">
                  <tr>
                    <th className="px-2 py-2">结果</th>
                    <th className="px-2 py-2">检查</th>
                    <th className="px-2 py-2">Node</th>
                    <th className="px-2 py-2">目标</th>
                    <th className="px-2 py-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {checks.map((check: AnyMap, index: number) => (
                    <tr key={index} className="border-t border-default-100">
                      <td className="px-2 py-2">
                        <Chip size="sm" color={check.passed ? 'success' : 'danger'} variant="flat">
                          {check.passed ? 'PASS' : 'FAIL'}
                        </Chip>
                      </td>
                      <td className="px-2 py-2">{check.type || '-'}</td>
                      <td className="px-2 py-2">#{check.nodeId || '-'}</td>
                      <td className="px-2 py-2">{check.host ? `${check.host}:${check.port}` : check.port || '-'}</td>
                      <td className="px-2 py-2 text-default-500">{check.message || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </CardBody>
        </Card>
      )}

      <Card>
        <CardHeader className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 className="text-lg font-semibold">Deployment 记录</h2>
            <div className="text-xs text-default-500">只读查看 Apply / Rollback 审计状态</div>
          </div>
          <Button size="sm" variant="light" onPress={loadData}>刷新</Button>
        </CardHeader>
        <Divider />
        <CardBody>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-left text-sm">
              <thead className="text-xs uppercase text-default-500">
                <tr>
                  <th className="px-2 py-2">ID</th>
                  <th className="px-2 py-2">Chain</th>
                  <th className="px-2 py-2">状态</th>
                  <th className="px-2 py-2">fingerprint</th>
                  <th className="px-2 py-2">创建时间</th>
                </tr>
              </thead>
              <tbody>
                {deployments.map((deployment) => {
                  const state = String(valueOf(deployment, 'state') || 'UNKNOWN');
                  return (
                    <tr key={deployment.id} className="border-t border-default-100">
                      <td className="px-2 py-2">#{deployment.id}</td>
                      <td className="px-2 py-2">{valueOf(deployment, 'chain_name', 'chainName', 'chain_id', 'chainId') || '-'}</td>
                      <td className="px-2 py-2"><Chip size="sm" color={statusColor(state)} variant="flat">{state}</Chip></td>
                      <td className="px-2 py-2 font-mono text-xs">{shortFingerprint(valueOf(deployment, 'fingerprint'))}</td>
                      <td className="px-2 py-2">{displayTime(valueOf(deployment, 'created_time', 'createdTime'))}</td>
                    </tr>
                  );
                })}
                {deployments.length === 0 && (
                  <tr><td className="px-2 py-4 text-default-400" colSpan={5}>暂无 Deployment</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </CardBody>
      </Card>

      <Alert
        color="primary"
        title="下一步"
        description="这个页面故意没有 Apply。等三机真实灰度通过后，再增加需要二次确认和 fingerprint 校验的 Apply / Rollback UI，随后才进入自动故障切换。"
      />
    </div>
  );
}
