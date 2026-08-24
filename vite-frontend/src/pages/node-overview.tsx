import { Button } from '@heroui/button';
import { useNavigate } from 'react-router-dom';

import NodePage from '@/pages/node';

/**
 * 在不侵入原节点卡片大组件的前提下，给监控页补一个明确的网络质量入口。
 */
export default function NodeOverviewPage() {
  const navigate = useNavigate();

  return (
    <div>
      <div className="px-3 lg:px-6 pt-4 flex justify-end">
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
