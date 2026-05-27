/// Блок длинного документа (terms / privacy / правила).
///
/// `type` — один из: `h1` (заголовок документа), `h2` (раздел),
/// `p` (параграф), `li` (буллет-пункт), `def` (определение: «термин — толкование»).
class DocBlock {
  const DocBlock(this.type, this.text);
  final String type;
  final String text;
}
