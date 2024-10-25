from dataclasses import dataclass
from datetime import datetime

from datahub.ingestion.source.sql.sql_generic_profiler import ProfilingSqlReport
from datahub.ingestion.source.state.stale_entity_removal_handler import (
    StaleEntityRemovalSourceReport,
)


@dataclass
class DremioSourceReport(ProfilingSqlReport, StaleEntityRemovalSourceReport):
    num_containers_failed: int = 0
    num_datasets_failed: int = 0

    def report_upstream_latency(self, start_time: datetime, end_time: datetime) -> None:
        # recording total combined latency is not very useful, keeping this method as a placeholder
        # for future implementation of min / max / percentiles etc.
        pass

    def report_entity_scanned(self, name: str, ent_type: str = "View") -> None:
        """
        Entity could be a view or a table
        """
        if ent_type == "Table":
            self.tables_scanned += 1
        elif ent_type == "View":
            self.views_scanned += 1
        else:
            raise KeyError(f"Unknown entity {ent_type}.")
