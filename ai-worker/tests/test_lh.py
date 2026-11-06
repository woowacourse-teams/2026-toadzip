from ai_worker import lh
from ai_worker.lh import LhSupplyRow
from conftest import contract_example


def test_input_example_parses_as_lh_rows():
    assert lh.lh_rows(contract_example("input.example.json")) == [
        LhSupplyRow("16A(대학생/청년)", "16.37", "27.6384", "78", "LH:2015122300018207:SUPPLY:0"),
    ]
