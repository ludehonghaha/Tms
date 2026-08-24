import { useEffect, useMemo, useState } from 'react';
import { Card, CardBody, CardHeader } from '@heroui/card';
import { Button } from '@heroui/button';
import { Chip } from '@heroui/chip';
import { Spinner } from '@heroui/spinner';
import toast from 'react-hot-toast';

import { getTrafficStatistics } from '@/api';

type UserTraffic = {
  userId: number;
  username: string;
  status: number;
  today: number;
  yesterday: number;
  periodTotal: number;
  currentTotal: number;
  flowLimitGb?: number;
  daily: Record<string, number>;
};

type TrafficResponse = {
  days: string[];
  range: number;
  generatedAt: number;
  users: UserTraffic[];
};

const formatBytes = (value: number) => {
  const bytes = Number(value || 0);
  if (bytes < 1024) return `${bytes.toFixed(0)} B`;
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(2)} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(2)} MB`;
  if (bytes < 1024 ** 4) return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
  return `${(bytes / 1024 ** 4).toFixed(2)} TB`;
};

const shortDate = (date: string) => {
  const parts = date.split('-');
  return parts.length === 3 ? `${parts[1]}/${parts[2]}` : date;
};

export default function TrafficStatisticsPage() {
  const [days, setDays] = useState(7);
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState<TrafficResponse | null>(null);

  useEffect(() => {
    loadData(days);
  }, [days]);

  const loadData = async (range: number) => {
    setLoading(true);
    try {
      const response = await getTrafficStatistics(range);
      if (response.code === 0) {
        setData(response.data as TrafficResponse);
      } else {
        toast.error(response.msg || '获取用户流量统计失败');
      }
    } catch (error) {
      toast.error('获取用户流量统计失败');
    } finally {
      setLoading(false);
    }
  };

  const sortedUsers = useMemo(() => {
    return [...(data?.users || [])].sort((a, b) => b.today - a.today || b.periodTotal - a.periodTotal);
  }, [data]);

  const maxDaily = useMemo(() => {
    let max = 0;
    sortedUsers.forEach(user => {
      Object.values(user.daily || {}).forEach(value => {
        max = Math.max(max, Number(value || 0));
      });
    });
    return max;
  }, [sortedUsers]);

  return (
    <div className="px-3 lg:px-6 py-6 space-y-5">
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">单用户流量统计</h1>
          <p className="text-sm text-default-500 mt-1">管理员按用户查看每天的实际计费流量，数据按小时落桶、按分钟更新。</p>
        </div>
        <div className="flex gap-2">
          {[7, 14, 31].map(range => (
            <Button
              key={range}
              size="sm"
              color={days === range ? 'primary' : 'default'}
              variant={days === range ? 'solid' : 'flat'}
              onPress={() => setDays(range)}
            >
              {range === 31 ? '31 天' : `${range} 天`}
            </Button>
          ))}
          <Button size="sm" variant="flat" onPress={() => loadData(days)} isLoading={loading}>刷新</Button>
        </div>
      </div>

      {loading && !data ? (
        <Card className="border border-divider shadow-sm">
          <CardBody className="py-12 flex items-center justify-center gap-2 text-default-500">
            <Spinner size="sm" /> 正在加载统计...
          </CardBody>
        </Card>
      ) : (
        <div className="space-y-4">
          {sortedUsers.length === 0 && (
            <Card className="border border-divider shadow-sm">
              <CardBody className="py-10 text-center text-default-500">暂无用户流量数据</CardBody>
            </Card>
          )}

          {sortedUsers.map(user => (
            <Card key={user.userId} className="border border-divider shadow-sm">
              <CardHeader className="flex flex-col md:flex-row md:items-center md:justify-between gap-3">
                <div className="flex items-center gap-2">
                  <div>
                    <div className="font-medium">{user.username || `用户 #${user.userId}`}</div>
                    <div className="text-xs text-default-400 mt-0.5">ID {user.userId}</div>
                  </div>
                  <Chip size="sm" color={user.status === 1 ? 'success' : 'default'} variant="flat">
                    {user.status === 1 ? '启用' : '停用'}
                  </Chip>
                </div>
                <div className="grid grid-cols-3 gap-5 text-right">
                  <div>
                    <div className="text-xs text-default-400">今天</div>
                    <div className="font-mono text-sm font-medium">{formatBytes(user.today)}</div>
                  </div>
                  <div>
                    <div className="text-xs text-default-400">昨天</div>
                    <div className="font-mono text-sm font-medium">{formatBytes(user.yesterday)}</div>
                  </div>
                  <div>
                    <div className="text-xs text-default-400">{days} 天合计</div>
                    <div className="font-mono text-sm font-medium">{formatBytes(user.periodTotal)}</div>
                  </div>
                </div>
              </CardHeader>
              <CardBody className="pt-1 space-y-3">
                <div className="overflow-x-auto pb-1">
                  <div className="flex gap-2 min-w-max items-end h-24">
                    {(data?.days || []).map(date => {
                      const value = Number(user.daily?.[date] || 0);
                      const height = maxDaily > 0 ? Math.max(3, Math.round(value / maxDaily * 64)) : 3;
                      return (
                        <div key={date} className="w-12 flex flex-col items-center justify-end gap-1" title={`${date}: ${formatBytes(value)}`}>
                          <div className="text-[10px] text-default-400 font-mono">{value > 0 ? formatBytes(value) : '0'}</div>
                          <div className="w-5 rounded-t bg-primary/70" style={{ height: `${height}px` }} />
                          <div className="text-[10px] text-default-400">{shortDate(date)}</div>
                        </div>
                      );
                    })}
                  </div>
                </div>
                <div className="flex flex-wrap gap-x-5 gap-y-1 text-xs text-default-500 border-t border-divider pt-3">
                  <span>账号当前累计：<span className="font-mono text-foreground">{formatBytes(user.currentTotal)}</span></span>
                  <span>套餐额度：<span className="font-mono text-foreground">{user.flowLimitGb === 99999 ? '不限量' : `${user.flowLimitGb || 0} GB`}</span></span>
                </div>
              </CardBody>
            </Card>
          ))}

          {data && (
            <div className="text-xs text-default-400 text-right">
              最后生成：{new Date(data.generatedAt).toLocaleString('zh-CN')}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
