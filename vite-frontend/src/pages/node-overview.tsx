import { Button } from '@heroui/button';
import { useNavigate } from 'react-router-dom';

import NodePage from '@/pages/node';

/**
 * 在不侵入原节点卡片大组件的前提下，给监控相关功能提供明确入口。
 */
export default function NodeOverviewPage() {
  const navigate = useNavigate();

  return (
    <div>
      <div className="px-3 lg:px-6 pt-4 flex justify-end gap-2">
        <Button
          size="sm"
          color="default"
          variant="flat"
          onPress={() => navigate('/traffic-statistics')}
        >
          单用户流量统计
        </Button>
        <Button
          size="sm"
          color="primary"
          variant="flat"
          onPress={() => navigate('/network-quality')}
        >
          网络质量检测
        </Button>
      </div>
      <NodePage />
    </div>
  );
}
