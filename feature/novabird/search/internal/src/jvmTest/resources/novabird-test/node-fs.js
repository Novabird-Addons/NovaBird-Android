// Ersatz für node:fs (nur readFileSync für die Testfälle aus tests/*.json).
const fs = {
  readFileSync(path) {
    return __nbReadFile(String(path));
  },
};
export default fs;
